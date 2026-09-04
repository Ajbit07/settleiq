package com.settleiq;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Phase 5 -- append-only, hash-chained audit ledger.
 *
 * Each row stores SHA-256( previous_hash || canonical_json(current_row) ).
 * Canonical JSON means keys in a fixed order and no incidental whitespace, so
 * the hash depends on the FACTS and not on serialisation accidents.
 *
 * Idempotency: every posting carries a key derived from the inputs it was
 * computed from. Re-running the same batch re-derives the same key, finds it
 * already present, and writes nothing. That is what makes a reconciliation run
 * safe to retry -- the property that actually matters in production, where the
 * job will be retried after a timeout and must not double-post.
 *
 * The DDL in db/schema.sql defines the same structure as a Postgres table for
 * the containerised deployment; this file-backed implementation has identical
 * semantics and is what the offline demo runs against.
 */
public final class Audit implements AuditSink {

    public record Entry(long seq, String timestamp, String actor, String action,
                        String entityType, String entityId, String idempotencyKey,
                        String modelVersion, double score, String verdict,
                        Map<String, String> payload, String prevHash, String hash) {}

    private final Path path;
    private final List<Entry> entries = new ArrayList<>();
    private final Map<String, String> idempotencyKeys = new LinkedHashMap<>();
    private String head = "0".repeat(64);
    private long seq = 0;
    private int suppressed = 0;

    public Audit(Path path) {
        this.path = path;
        if (Files.exists(path)) load();
    }

    @Override public int suppressedDuplicates() { return suppressed; }
    @Override public int appendedCount() { return entries.size(); }
    public List<Entry> entries() { return entries; }
    @Override public String head() { return head; }
    @Override public boolean seen(String idempotencyKey) { return idempotencyKeys.containsKey(idempotencyKey); }
    @Override public String priorVerdict(String key) { return idempotencyKeys.get(key); }

    private void load() {
        try {
            for (String line : Files.readAllLines(path, StandardCharsets.UTF_8)) {
                if (line.isBlank()) continue;
                @SuppressWarnings("unchecked")
                Map<String, Object> m = (Map<String, Object>) Csv.json(line);
                String k = (String) m.get("idempotency_key");
                if (k != null) idempotencyKeys.put(k, (String) m.get("verdict"));
                head = (String) m.get("hash");
                seq = (long) (double) (Double) m.get("seq");
            }
        } catch (IOException e) {
            throw new RuntimeException("loading audit ledger", e);
        }
    }

    /** Canonical JSON: fixed key order, no incidental whitespace. */
    /** Canonical JSON, shared by every AuditSink so chains are interchangeable. */
    public static String canonical(long seq, String ts, String actor, String action, String et,
                            String eid, String ik, String mv, double score, String verdict,
                            Map<String, String> payload, String prev) {
        StringBuilder sb = new StringBuilder();
        sb.append("{\"seq\":").append(seq)
          .append(",\"timestamp\":").append(Csv.q(ts))
          .append(",\"actor\":").append(Csv.q(actor))
          .append(",\"action\":").append(Csv.q(action))
          .append(",\"entity_type\":").append(Csv.q(et))
          .append(",\"entity_id\":").append(Csv.q(eid))
          .append(",\"idempotency_key\":").append(Csv.q(ik))
          .append(",\"model_version\":").append(Csv.q(mv))
          .append(",\"score\":").append(String.format("%.6f", score))
          .append(",\"verdict\":").append(Csv.q(verdict))
          .append(",\"payload\":{");
        List<String> keys = new ArrayList<>(payload.keySet());
        java.util.Collections.sort(keys);
        for (int i = 0; i < keys.size(); i++) {
            if (i > 0) sb.append(',');
            sb.append(Csv.q(keys.get(i))).append(':').append(Csv.q(payload.get(keys.get(i))));
        }
        sb.append("},\"prev_hash\":").append(Csv.q(prev)).append('}');
        return sb.toString();
    }

    public static String sha256(String s) {
        try {
            MessageDigest md = MessageDigest.getInstance("SHA-256");
            return HexFormat.of().formatHex(md.digest(s.getBytes(StandardCharsets.UTF_8)));
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
    }

    /**
     * Append one entry. Returns false and writes NOTHING when the idempotency
     * key has already been posted.
     */
    @Override
    public boolean append(String actor, String action, String entityType, String entityId,
                          String idempotencyKey, String modelVersion, double score,
                          String verdict, Map<String, String> payload) {
        if (idempotencyKeys.containsKey(idempotencyKey)) { suppressed++; return false; }
        long s = ++seq;
        String ts = java.time.OffsetDateTime.now(java.time.ZoneOffset.ofHoursMinutes(5, 30))
                .withNano(0).toString();
        String body = canonical(s, ts, actor, action, entityType, entityId, idempotencyKey,
                modelVersion, score, verdict, payload, head);
        String h = sha256(head + body);
        String line = body.substring(0, body.length() - 1) + ",\"hash\":" + Csv.q(h) + "}";
        try {
            Files.createDirectories(path.getParent());
            Files.writeString(path, line + "\n", StandardCharsets.UTF_8,
                    StandardOpenOption.CREATE, StandardOpenOption.APPEND);
        } catch (IOException e) {
            throw new RuntimeException("appending audit ledger", e);
        }
        entries.add(new Entry(s, ts, actor, action, entityType, entityId, idempotencyKey,
                modelVersion, score, verdict, new LinkedHashMap<>(payload), head, h));
        idempotencyKeys.put(idempotencyKey, verdict);
        head = h;
        return true;
    }

    public record Verification(boolean valid, long rows, String head, String failureAt) {}

    /** Walk the chain from genesis and prove no row was altered, inserted or dropped. */
    public static Verification verify(Path path) {
        if (!Files.exists(path)) return new Verification(true, 0, "0".repeat(64), null);
        try {
            String prev = "0".repeat(64);
            long n = 0;
            for (String line : Files.readAllLines(path, StandardCharsets.UTF_8)) {
                if (line.isBlank()) continue;
                n++;
                int cut = line.lastIndexOf(",\"hash\":");
                if (cut < 0) return new Verification(false, n, prev, "row " + n + ": no hash field");
                String body = line.substring(0, cut) + "}";
                String stated = line.substring(cut + 8).replace("}", "").replace("\"", "").trim();
                String recomputed = sha256(prev + body);
                if (!recomputed.equals(stated))
                    return new Verification(false, n, prev, "row " + n + ": hash mismatch");
                @SuppressWarnings("unchecked")
                Map<String, Object> m = (Map<String, Object>) Csv.json(line);
                if (!prev.equals(m.get("prev_hash")))
                    return new Verification(false, n, prev, "row " + n + ": broken prev_hash link");
                prev = stated;
            }
            return new Verification(true, n, prev, null);
        } catch (IOException e) {
            throw new RuntimeException("verifying audit ledger", e);
        }
    }

    /** Idempotency key: stable function of the facts that produced a posting. */
    public static String key(String merchantId, String entityId, String action, String... parts) {
        return sha256(merchantId + "|" + entityId + "|" + action + "|" + String.join("|", parts))
                .substring(0, 32);
    }
}
