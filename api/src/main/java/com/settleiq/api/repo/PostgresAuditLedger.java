package com.settleiq.api.repo;

import com.settleiq.Audit;
import com.settleiq.AuditSink;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.jdbc.core.JdbcTemplate;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * Postgres-backed audit ledger.
 *
 * Idempotency is enforced by the UNIQUE constraint on idempotency_key, not by
 * a prior SELECT. That distinction matters: two workers that race on the same
 * key both pass a SELECT check and both insert, so the check-then-act version
 * double-posts under exactly the concurrency it was written to survive. Here
 * the second INSERT raises, we catch it, and report the posting as suppressed.
 *
 * The hash chain is computed with the SAME canonical JSON routine the
 * file-backed ledger uses, so a chain written by the CLI verifies in the API
 * and vice versa.
 */
public class PostgresAuditLedger implements AuditSink {

    private static final String ZERO = "0".repeat(64);

    private final JdbcTemplate jdbc;
    private final String merchantId;
    private final Long runId;
    private String head;
    private long seq;
    private int suppressed;
    private int appended;

    public PostgresAuditLedger(JdbcTemplate jdbc, String merchantId, Long runId) {
        this.jdbc = jdbc;
        this.merchantId = merchantId;
        this.runId = runId;
        Map<String, Object> row = headRow(jdbc, merchantId);
        this.head = row == null ? ZERO : (String) row.get("hash");
        this.seq = row == null ? 0L : ((Number) row.get("seq")).longValue();
    }

    private static Map<String, Object> headRow(JdbcTemplate jdbc, String merchantId) {
        List<Map<String, Object>> r = jdbc.queryForList(
                "SELECT seq, hash FROM audit_ledger WHERE merchant_id = ? "
                + "ORDER BY seq DESC LIMIT 1", merchantId);
        return r.isEmpty() ? null : r.get(0);
    }

    @Override
    public boolean seen(String idempotencyKey) {
        Integer n = jdbc.queryForObject(
                "SELECT count(*) FROM audit_ledger WHERE idempotency_key = ?",
                Integer.class, idempotencyKey);
        return n != null && n > 0;
    }

    @Override
    public String priorVerdict(String idempotencyKey) {
        var r = jdbc.queryForList(
                "SELECT verdict FROM audit_ledger WHERE idempotency_key = ?", idempotencyKey);
        return r.isEmpty() ? null : (String) r.get(0).get("verdict");
    }

    @Override
    public boolean append(String actor, String action, String entityType, String entityId,
                          String idempotencyKey, String modelVersion, double score,
                          String verdict, Map<String, String> payload) {
        long next = seq + 1;
        String ts = java.time.OffsetDateTime
                .now(java.time.ZoneOffset.ofHoursMinutes(5, 30)).withNano(0).toString();
        String body = Audit.canonical(next, ts, actor, action, entityType, entityId,
                idempotencyKey, modelVersion, score, verdict, payload, head);
        String hash = Audit.sha256(head + body);

        try {
            jdbc.update("""
                    INSERT INTO audit_ledger
                      (run_id, merchant_id, actor, action, entity_type, entity_id,
                       idempotency_key, model_version, score, verdict, payload,
                       prev_hash, hash, canon_ts, canon_seq)
                    VALUES (?,?,?,?,?,?,?,?,?,?,?::jsonb,?,?,?,?)
                    """,
                    runId, merchantId, actor, action, entityType, entityId,
                    idempotencyKey, modelVersion, score, verdict, toJson(payload),
                    head, hash, ts, next);
        } catch (DuplicateKeyException e) {
            // The UNIQUE constraint did its job: this exact posting already
            // exists. Nothing is written and the chain head does not move.
            suppressed++;
            return false;
        }
        head = hash;
        seq = next;
        appended++;
        return true;
    }

    private static String toJson(Map<String, String> payload) {
        List<String> keys = new ArrayList<>(payload.keySet());
        java.util.Collections.sort(keys);
        StringBuilder sb = new StringBuilder("{");
        for (int i = 0; i < keys.size(); i++) {
            if (i > 0) sb.append(',');
            sb.append(com.settleiq.Csv.q(keys.get(i))).append(':')
              .append(com.settleiq.Csv.q(payload.get(keys.get(i))));
        }
        return sb.append('}').toString();
    }

    @Override public String head() { return head; }
    @Override public int suppressedDuplicates() { return suppressed; }
    @Override public int appendedCount() { return appended; }

    /** Server-side chain walk. Empty result means intact. */
    public record Break(long seq, String reason) {}

    /**
     * Full verification: links AND content.
     *
     * The SQL walk proves the rows still form one unbroken chain -- it catches
     * a deleted row, a forged insert and a rewritten prev_hash. It does NOT
     * catch an edit to the row's own columns, because it never recomputes a
     * hash from them. That gap made every money-bearing field silently
     * rewritable by anyone with database access, which is most of the point of
     * having a ledger.
     *
     * So the content walk below re-derives sha256(prev_hash || canonical(row))
     * for every row from the stored columns, using the SAME canonicalisation
     * that wrote it, and compares. One source of truth for the byte layout: a
     * reimplementation in plpgsql would drift from the Java on the first schema
     * change and start reporting phantom breaks.
     */
    public static List<Break> verify(JdbcTemplate jdbc, String merchantId) {
        List<Break> breaks = new ArrayList<>(jdbc.query(
                "SELECT broken_seq, reason FROM audit_chain_break(?)",
                (rs, i) -> new Break(rs.getLong(1), rs.getString(2)), merchantId));
        breaks.addAll(verifyContent(jdbc, merchantId));
        return breaks;
    }

    /** Recomputes each row's hash from its own stored columns. */
    public static List<Break> verifyContent(JdbcTemplate jdbc, String merchantId) {
        List<Break> out = new ArrayList<>();
        List<Map<String, Object>> rows = jdbc.queryForList("""
                SELECT seq, canon_ts, canon_seq, actor, action, entity_type, entity_id, idempotency_key,
                       model_version, score, verdict, payload, prev_hash, hash
                  FROM audit_ledger WHERE merchant_id = ? ORDER BY seq
                """, merchantId);
        for (Map<String, Object> r : rows) {
            long seq = ((Number) r.get("seq")).longValue();
            String canonTs = (String) r.get("canon_ts");
            Object canonSeq = r.get("canon_seq");
            if (canonTs == null || canonSeq == null) {
                // Written before V7: the timestamp that was hashed was never
                // stored, so this row cannot be re-canonicalised. Say that,
                // rather than reporting it as verified.
                out.add(new Break(seq, "unverifiable: predates content verification (V7)"));
                continue;
            }
            String recomputed = Audit.sha256((String) r.get("prev_hash")
                    + Audit.canonical(((Number) canonSeq).longValue(), canonTs,
                        (String) r.get("actor"), (String) r.get("action"),
                        (String) r.get("entity_type"), (String) r.get("entity_id"),
                        (String) r.get("idempotency_key"), (String) r.get("model_version"),
                        ((Number) r.get("score")).doubleValue(), (String) r.get("verdict"),
                        payloadOf(r.get("payload")), (String) r.get("prev_hash")));
            if (!recomputed.equals(String.valueOf(r.get("hash")).trim()))
                out.add(new Break(seq, "row content does not match its stored hash"));
        }
        return out;
    }

    /** JSONB payload back to the sorted string map the canonical form expects. */
    @SuppressWarnings("unchecked")
    private static Map<String, String> payloadOf(Object jsonb) {
        Object parsed = com.settleiq.Csv.json(String.valueOf(
                jsonb instanceof org.postgresql.util.PGobject p ? p.getValue() : jsonb));
        Map<String, String> m = new java.util.TreeMap<>();
        if (parsed instanceof Map<?, ?> raw)
            for (var e : raw.entrySet()) m.put(String.valueOf(e.getKey()), stringify(e.getValue()));
        return m;
    }

    /** Values were written as JSON strings, so they read back as strings. */
    private static String stringify(Object v) {
        if (v == null) return "";
        if (v instanceof Double d && d == Math.floor(d) && !d.isInfinite())
            return String.valueOf(d.longValue());
        return String.valueOf(v);
    }
}
