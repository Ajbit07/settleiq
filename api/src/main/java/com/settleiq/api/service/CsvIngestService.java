package com.settleiq.api.service;

import com.settleiq.Money;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionTemplate;

import java.nio.charset.CharacterCodingException;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Validating CSV ingestion.
 *
 * Every row is checked before it can reach a financial table, and every row
 * that fails is STORED with its reason and its raw text. That second half is
 * the point: a count of rejects nobody can inspect is indistinguishable from a
 * number someone made up, and the failure mode this system exists to prevent is
 * exactly a silent drop that surfaces later as a confident wrong total.
 *
 * What is actually validated, per row:
 *   - arity against the declared header, so a shifted column cannot be read as
 *     a different field that happens to parse
 *   - identifiers present and non-blank
 *   - money as an exact decimal with at most two places, converted to integer
 *     paise through the engine's own Money parser -- never through a double
 *   - dates and timestamps parsed strictly, no lenient roll-over
 *   - duplicates, both within the file and against rows already committed for
 *     this merchant
 *
 * Rejection is per row, not per file. A file with fourteen bad rows commits the
 * other 4,998 and reports both numbers, because refusing an entire day's
 * payments over one malformed amount is its own kind of outage.
 */
@Service
public class CsvIngestService {

    private static final Logger log = LoggerFactory.getLogger(CsvIngestService.class);

    /** Upload ceiling. Rejected before a byte is parsed. */
    public static final long MAX_BYTES = 64L * 1024 * 1024;

    private final JdbcTemplate jdbc;
    private final TransactionTemplate tx;

    public CsvIngestService(JdbcTemplate jdbc, TransactionTemplate tx) {
        this.jdbc = jdbc;
        this.tx = tx;
    }

    public record Reject(int lineNo, String reasonCode, String detail, String rawLine) {}

    public record Report(long ingestionId, String merchantId, String entity, String sourceName,
                         int rowsSeen, int rowsAccepted, int rowsRejected,
                         Map<String, Integer> rejectsByReason, List<Reject> sample,
                         String state, String error) {}

    /** The entity types an upload may target, and the header each one requires. */
    public enum Entity {
        PAYMENTS("payment", "payment_id,order_id,amount,method,status,created_at_ist"),
        REFUNDS("refund", "refund_id,payment_id,amount,created_at_ist,netted_flag"),
        SETTLEMENTS("settlement", "settlement_id,utr,gross,fees,gst,tds,net,settled_at,instant"),
        BANK("bank_txn", "bank_txn_id,value_date,amount,narration"),
        CHARGEBACKS("chargeback", "dispute_id,payment_id,amount,fee,raised_at_ist,reversed_at_ist"),
        RESERVE("reserve_entry", "reserve_id,amount,held_on,release_date,released_at_ist,netted_flag"),
        ORDERS("order_row", "order_id,customer_id,amount,created_at_utc");

        public final String table;
        public final String header;
        Entity(String table, String header) { this.table = table; this.header = header; }

        public static Entity of(String s) {
            for (Entity e : values()) if (e.name().equalsIgnoreCase(s)) return e;
            throw new IllegalArgumentException("unknown entity '" + s + "'; expected one of "
                    + java.util.Arrays.toString(values()));
        }
    }

    /**
     * Ingest one uploaded file.
     *
     * The run row is committed in its own transaction FIRST, so a crash midway
     * still leaves a record that an ingestion was attempted. A run that only
     * exists if it succeeded cannot tell you about the one that did not.
     */
    public Report ingest(String merchantId, Entity entity, String sourceName, byte[] bytes) {
        if (bytes.length == 0) throw new IllegalArgumentException("empty upload");
        if (bytes.length > MAX_BYTES)
            throw new IllegalArgumentException("upload exceeds " + (MAX_BYTES / 1024 / 1024) + " MB");

        Integer known = jdbc.queryForObject(
                "SELECT count(*) FROM merchant WHERE merchant_id = ?", Integer.class, merchantId);
        if (known == null || known == 0)
            throw new IllegalArgumentException("unknown merchant '" + merchantId + "'");

        String sha = sha256(bytes);
        long id = jdbc.queryForObject("""
                INSERT INTO ingestion_run
                  (merchant_id, entity, source_name, source_bytes, source_sha256, state)
                VALUES (?,?,?,?,?, 'running') RETURNING ingestion_id
                """, Long.class, merchantId, entity.name(), sourceName, (long) bytes.length, sha);

        try {
            Report r = parseAndCommit(id, merchantId, entity, sourceName, bytes);
            jdbc.update("""
                    UPDATE ingestion_run SET rows_seen=?, rows_accepted=?, rows_rejected=?,
                           state='succeeded', finished_at=now() WHERE ingestion_id=?
                    """, r.rowsSeen(), r.rowsAccepted(), r.rowsRejected(), id);
            log.info("ingestion {} merchant={} entity={} seen={} accepted={} rejected={}",
                    id, merchantId, entity, r.rowsSeen(), r.rowsAccepted(), r.rowsRejected());
            return r;
        } catch (RuntimeException e) {
            jdbc.update("UPDATE ingestion_run SET state='failed', error=?, finished_at=now() "
                    + "WHERE ingestion_id=?", trunc(e.getMessage(), 900), id);
            log.warn("ingestion {} failed: {}", id, e.toString());
            throw e;
        }
    }

    /**
     * Decodes strictly as UTF-8, stripping a BOM if present.
     *
     * Strict rather than replacing: a malformed byte silently becoming U+FFFD
     * inside a narration would corrupt the reference the matcher reads, and
     * would do it invisibly. Better to reject the file and say why.
     */
    static String decode(byte[] bytes) {
        var dec = StandardCharsets.UTF_8.newDecoder()
                .onMalformedInput(CodingErrorAction.REPORT)
                .onUnmappableCharacter(CodingErrorAction.REPORT);
        try {
            String s = dec.decode(java.nio.ByteBuffer.wrap(bytes)).toString();
            return s.startsWith("﻿") ? s.substring(1) : s;
        } catch (CharacterCodingException e) {
            throw new IllegalArgumentException("file is not valid UTF-8: " + e.getMessage());
        }
    }

    /**
     * Parses the whole file, then commits accepted rows and reject records in
     * ONE transaction.
     *
     * Explicitly via TransactionTemplate rather than @Transactional: this is
     * called from `ingest` on the same bean, and Spring's proxy does not
     * intercept self-invocation, so the annotation would have been decorative.
     * A half-applied ingestion whose reject rows committed and whose accepted
     * rows did not would misreport the file permanently.
     */
    Report parseAndCommit(long id, String merchantId, Entity entity,
                          String sourceName, byte[] bytes) {
        String text = decode(bytes);
        String[] lines = text.split("\r?\n");
        if (lines.length == 0 || lines[0].isBlank())
            throw new IllegalArgumentException("file has no header row");

        String header = lines[0].trim();
        if (!header.equalsIgnoreCase(entity.header))
            throw new IllegalArgumentException("header mismatch for " + entity.name()
                    + "\n  expected: " + entity.header + "\n  received: " + header);

        int cols = entity.header.split(",").length;
        List<Reject> rejects = new ArrayList<>();
        List<Object[]> accepted = new ArrayList<>();
        Set<String> seenIds = new HashSet<>();
        Set<String> existing = existingIds(merchantId, entity);
        int seen = 0;

        for (int i = 1; i < lines.length; i++) {
            String raw = lines[i];
            if (raw.isBlank()) continue;
            seen++;
            int lineNo = i + 1;
            try {
                String[] f = splitCsv(raw);
                if (f.length != cols)
                    throw new Bad("ARITY", "expected " + cols + " columns, found " + f.length);

                Object[] row = switch (entity) {
                    case PAYMENTS    -> payment(merchantId, id, f);
                    case REFUNDS     -> refund(merchantId, id, f);
                    case SETTLEMENTS -> settlement(merchantId, id, f);
                    case BANK        -> bank(merchantId, id, f);
                    case CHARGEBACKS -> chargeback(merchantId, id, f);
                    case RESERVE     -> reserve(merchantId, id, f);
                    case ORDERS      -> order(merchantId, id, f);
                };
                String key = String.valueOf(row[1]);          // the source id, by construction
                if (!seenIds.add(key))
                    throw new Bad("DUPLICATE_IN_FILE", "id '" + key + "' appears earlier in this file");
                if (existing.contains(key))
                    throw new Bad("DUPLICATE_EXISTING", "id '" + key + "' is already ingested for this merchant");
                accepted.add(row);
            } catch (Bad b) {
                rejects.add(new Reject(lineNo, b.code, b.getMessage(), trunc(raw, 400)));
            } catch (RuntimeException e) {
                rejects.add(new Reject(lineNo, "UNPARSEABLE",
                        e.getClass().getSimpleName() + ": " + e.getMessage(), trunc(raw, 400)));
            }
        }

        tx.executeWithoutResult(status -> {
            if (!accepted.isEmpty()) jdbc.batchUpdate(insertSql(entity), accepted);
            if (!rejects.isEmpty()) {
                List<Object[]> rr = new ArrayList<>();
                for (Reject r : rejects)
                    rr.add(new Object[]{id, r.lineNo(), r.reasonCode(), r.detail(), r.rawLine()});
                jdbc.batchUpdate("INSERT INTO ingestion_reject "
                        + "(ingestion_id, line_no, reason_code, detail, raw_line) VALUES (?,?,?,?,?)", rr);
            }
        });

        Map<String, Integer> byReason = new LinkedHashMap<>();
        for (Reject r : rejects) byReason.merge(r.reasonCode(), 1, Integer::sum);

        return new Report(id, merchantId, entity.name(), sourceName, seen,
                accepted.size(), rejects.size(), byReason,
                rejects.size() > 20 ? rejects.subList(0, 20) : rejects, "succeeded", null);
    }

    /** A row-level validation failure. Carries a stable code the UI can group by. */
    private static final class Bad extends RuntimeException {
        final String code;
        Bad(String code, String msg) { super(msg); this.code = code; }
    }

    // ─────────────────────────────────────────────────────────── row builders
    // Column order matches insertSql(); index 1 is always the source id, which
    // is what the duplicate check keys on.

    private Object[] payment(String m, long ing, String[] f) {
        return new Object[]{m, id(f[0], "payment_id"), blankToNull(f[1]), money(f[2], "amount"),
                nonBlank(f[3], "method"), nonBlank(f[4], "status"), ts(f[5], "created_at_ist"), ing};
    }

    private Object[] refund(String m, long ing, String[] f) {
        return new Object[]{m, id(f[0], "refund_id"), nonBlank(f[1], "payment_id"),
                money(f[2], "amount"), ts(f[3], "created_at_ist"), bool(f[4], "netted_flag"), ing};
    }

    private Object[] settlement(String m, long ing, String[] f) {
        long gross = money(f[2], "gross"), fees = money(f[3], "fees");
        long gst = money(f[4], "gst"), tds = money(f[5], "tds"), net = money(f[6], "net");
        // The settlement report must be internally consistent before it is
        // allowed to become the thing every downstream figure is measured
        // against. A report whose own columns do not add up is a source-system
        // fault, not a reconciliation break, and must not be silently reconciled.
        if (gross - fees - gst - tds != net)
            throw new Bad("MONETARY_INCONSISTENT", "gross - fees - gst - tds = "
                    + Money.fmtInr(gross - fees - gst - tds) + " but net reads " + Money.fmtInr(net));
        return new Object[]{m, id(f[0], "settlement_id"), blankToNull(f[1]), gross, fees, gst, tds,
                net, ts(f[7], "settled_at"), bool(f[8], "instant"), ing};
    }

    private Object[] bank(String m, long ing, String[] f) {
        return new Object[]{m, id(f[0], "bank_txn_id"), date(f[1], "value_date"),
                signedMoney(f[2], "amount"), nonBlank(f[3], "narration"), ing};
    }

    private Object[] chargeback(String m, long ing, String[] f) {
        return new Object[]{m, id(f[0], "dispute_id"), nonBlank(f[1], "payment_id"),
                money(f[2], "amount"), money(f[3], "fee"), ts(f[4], "raised_at_ist"),
                f[5] == null || f[5].isBlank() ? null : ts(f[5], "reversed_at_ist"), ing};
    }

    private Object[] reserve(String m, long ing, String[] f) {
        return new Object[]{m, id(f[0], "reserve_id"), money(f[1], "amount"),
                date(f[2], "held_on"), date(f[3], "release_date"),
                ts(f[4], "released_at_ist"), bool(f[5], "netted_flag"), ing};
    }

    private Object[] order(String m, long ing, String[] f) {
        return new Object[]{m, id(f[0], "order_id"), nonBlank(f[1], "customer_id"),
                money(f[2], "amount"), ts(f[3], "created_at_utc"), ing};
    }

    private static String insertSql(Entity e) {
        return switch (e) {
            case PAYMENTS -> """
                INSERT INTO payment (merchant_id, payment_id, order_id, amount_paise, method,
                                     status, created_at_ist, ingestion_id) VALUES (?,?,?,?,?,?,?,?)""";
            case REFUNDS -> """
                INSERT INTO refund (merchant_id, refund_id, payment_id, amount_paise,
                                    created_at_ist, netted, ingestion_id) VALUES (?,?,?,?,?,?,?)""";
            case SETTLEMENTS -> """
                INSERT INTO settlement (merchant_id, settlement_id, utr, gross_paise, fees_paise,
                                        gst_paise, tds_paise, net_paise, settled_at, instant,
                                        ingestion_id) VALUES (?,?,?,?,?,?,?,?,?,?,?)""";
            case BANK -> """
                INSERT INTO bank_txn (merchant_id, bank_txn_id, value_date, amount_paise,
                                      narration, ingestion_id) VALUES (?,?,?,?,?,?)""";
            case CHARGEBACKS -> """
                INSERT INTO chargeback (merchant_id, dispute_id, payment_id, amount_paise,
                                        fee_paise, raised_at_ist, reversed_at_ist, ingestion_id)
                VALUES (?,?,?,?,?,?,?,?)""";
            case RESERVE -> """
                INSERT INTO reserve_entry (merchant_id, reserve_id, amount_paise, held_on,
                                           release_date, released_at_ist, netted, ingestion_id)
                VALUES (?,?,?,?,?,?,?,?)""";
            case ORDERS -> """
                INSERT INTO order_row (merchant_id, order_id, customer_id, amount_paise,
                                       created_at_utc, ingestion_id) VALUES (?,?,?,?,?,?)""";
        };
    }

    /** Ids already committed for THIS merchant. Scoped, so tenants cannot collide. */
    private Set<String> existingIds(String merchantId, Entity e) {
        String col = switch (e) {
            case PAYMENTS -> "payment_id";  case REFUNDS -> "refund_id";
            case SETTLEMENTS -> "settlement_id"; case BANK -> "bank_txn_id";
            case CHARGEBACKS -> "dispute_id"; case RESERVE -> "reserve_id";
            case ORDERS -> "order_id";
        };
        return new HashSet<>(jdbc.queryForList(
                "SELECT " + col + " FROM " + e.table + " WHERE merchant_id = ?",
                String.class, merchantId));
    }

    // ───────────────────────────────────────────────────────────── validators

    private static String id(String s, String field) {
        if (s == null || s.isBlank()) throw new Bad("MISSING_ID", field + " is blank");
        if (s.length() > 128) throw new Bad("MISSING_ID", field + " exceeds 128 characters");
        return s.trim();
    }

    private static String nonBlank(String s, String field) {
        if (s == null || s.isBlank()) throw new Bad("MISSING_FIELD", field + " is blank");
        return s.trim();
    }

    private static String blankToNull(String s) {
        return s == null || s.isBlank() ? null : s.trim();
    }

    /**
     * Money as integer paise, non-negative.
     *
     * Deliberately does NOT accept scientific notation, thousands separators or
     * more than two decimal places. Each of those is a real thing that arrives
     * in real files, and each one silently changes the amount if you let a
     * double parse it. Rejecting is the only safe reading.
     */
    private static long money(String s, String field) {
        long v = signedMoney(s, field);
        if (v < 0) throw new Bad("MALFORMED_AMOUNT", field + " is negative: " + s);
        return v;
    }

    private static long signedMoney(String s, String field) {
        if (s == null || s.isBlank()) throw new Bad("MALFORMED_AMOUNT", field + " is blank");
        String t = s.trim();
        if (!t.matches("-?\\d{1,15}(\\.\\d{1,2})?"))
            throw new Bad("MALFORMED_AMOUNT", field + " is not a plain decimal with at most "
                    + "two places: '" + s + "'");
        try {
            return Money.parse(t);
        } catch (RuntimeException e) {
            throw new Bad("MALFORMED_AMOUNT", field + " could not be read as money: '" + s + "'");
        }
    }

    /**
     * Timestamp, normalised to the wall clock the column is declared in.
     *
     * The `_ist` columns are deliberately naive IST and the `_utc` ones naive
     * UTC, but real feeds emit offsets (`2025-11-22T15:52:57+05:30`). Storing
     * that offset text as if it were local time would shift every payment by
     * five and a half hours and silently move some across a settlement date
     * boundary -- so an offset is honoured and CONVERTED, using the same rule
     * as the engine's own loader (Loader.ist), never dropped.
     */
    private static java.sql.Timestamp ts(String s, String field) {
        if (s == null || s.isBlank()) throw new Bad("INVALID_DATE", field + " is blank");
        java.time.ZoneOffset target = field.endsWith("_utc")
                ? java.time.ZoneOffset.UTC
                : java.time.ZoneOffset.ofHoursMinutes(5, 30);
        String t = s.trim().replace(' ', 'T');
        try {
            return java.sql.Timestamp.valueOf(
                    java.time.OffsetDateTime.parse(t).withOffsetSameInstant(target).toLocalDateTime());
        } catch (DateTimeParseException ignored) { /* no offset present */ }
        try {
            return java.sql.Timestamp.valueOf(LocalDateTime.parse(t));
        } catch (DateTimeParseException ignored) { /* not a full timestamp */ }
        try {
            return java.sql.Timestamp.valueOf(LocalDate.parse(t).atStartOfDay());
        } catch (DateTimeParseException e) {
            throw new Bad("INVALID_DATE", field + " is not an ISO-8601 timestamp: '" + s + "'");
        }
    }

    private static java.sql.Date date(String s, String field) {
        if (s == null || s.isBlank()) throw new Bad("INVALID_DATE", field + " is blank");
        try {
            return java.sql.Date.valueOf(LocalDate.parse(s.trim()));
        } catch (DateTimeParseException e) {
            throw new Bad("INVALID_DATE", field + " is not an ISO-8601 date: '" + s + "'");
        }
    }

    private static boolean bool(String s, String field) {
        String t = s == null ? "" : s.trim().toLowerCase();
        return switch (t) {
            case "true", "t", "1", "yes", "y" -> true;
            case "false", "f", "0", "no", "n", "" -> false;
            default -> throw new Bad("MALFORMED_FIELD", field + " is not a boolean: '" + s + "'");
        };
    }

    /** Minimal RFC4180 split: quoted fields, doubled quotes, embedded commas. */
    static String[] splitCsv(String line) {
        List<String> out = new ArrayList<>();
        StringBuilder cur = new StringBuilder();
        boolean q = false;
        for (int i = 0; i < line.length(); i++) {
            char c = line.charAt(i);
            if (q) {
                if (c == '"') {
                    if (i + 1 < line.length() && line.charAt(i + 1) == '"') { cur.append('"'); i++; }
                    else q = false;
                } else cur.append(c);
            } else if (c == '"') q = true;
            else if (c == ',') { out.add(cur.toString()); cur.setLength(0); }
            else cur.append(c);
        }
        out.add(cur.toString());
        return out.toArray(new String[0]);
    }

    static String sha256(byte[] b) {
        try {
            byte[] d = MessageDigest.getInstance("SHA-256").digest(b);
            StringBuilder sb = new StringBuilder(64);
            for (byte x : d) sb.append(String.format("%02x", x));
            return sb.toString();
        } catch (Exception e) {
            throw new IllegalStateException("SHA-256 unavailable", e);
        }
    }

    private static String trunc(String s, int n) {
        if (s == null) return "";
        return s.length() <= n ? s : s.substring(0, n) + "…";
    }
}
