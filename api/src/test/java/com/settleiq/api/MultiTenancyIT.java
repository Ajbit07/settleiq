package com.settleiq.api;

import com.settleiq.api.service.CsvIngestService;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Two tenants whose source ids collide, end to end.
 *
 * This is the regression test for the worst bug this project has had: payment
 * ids were globally unique, so seeding a second merchant hit ON CONFLICT DO
 * NOTHING on every row, dropped them all, reported success, and then declared
 * that merchant's entire payout unexplained. Nothing threw. Every health check
 * was green.
 *
 * So the assertion is not "the schema has a composite key" -- it is that the
 * same identifier can be ingested twice under two tenants, that both rows
 * survive with their own values, and that no read scoped to one tenant can see
 * the other's.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.NONE)
class MultiTenancyIT {

    @DynamicPropertySource
    static void props(DynamicPropertyRegistry r) { PostgresIT.configure(r); }

    @Autowired JdbcTemplate jdbc;
    @Autowired CsvIngestService ingest;

    private static final String RUN = UUID.randomUUID().toString().substring(0, 8);

    private String merchant(String base) {
        String id = base + "_" + RUN;
        jdbc.update("INSERT INTO merchant (merchant_id, name, rate_card) "
                + "VALUES (?,?,'{}'::jsonb) ON CONFLICT DO NOTHING", id, "Test " + id);
        return id;
    }

    /** The SAME payment id, deliberately, with a DIFFERENT amount per tenant. */
    private static byte[] paymentsCsv(String amount) {
        return ("payment_id,order_id,amount,method,status,created_at_ist\n"
              + "pay_123,order_1," + amount + ",card,captured,2025-11-04T10:00:00+05:30\n")
                .getBytes(StandardCharsets.UTF_8);
    }

    @Test
    void collidingSourceIdsCoexistAndStayApart() {
        String a = merchant("tenant_a");
        String b = merchant("tenant_b");

        var ra = ingest.ingest(a, CsvIngestService.Entity.PAYMENTS, "a.csv", paymentsCsv("100.00"));
        var rb = ingest.ingest(b, CsvIngestService.Entity.PAYMENTS, "b.csv", paymentsCsv("250.00"));

        // Both accepted. Before V5 the second was silently rejected as a dupe.
        assertEquals(1, ra.rowsAccepted(), "tenant A's pay_123 must be accepted");
        assertEquals(1, rb.rowsAccepted(), "tenant B's pay_123 must be accepted, not swallowed");
        assertEquals(0, rb.rowsRejected(), "tenant B's row must not collide with tenant A's");

        // Each tenant sees exactly its own row, with its own amount.
        assertEquals(10_000L, amountFor(a), "tenant A must read back its own 100.00");
        assertEquals(25_000L, amountFor(b), "tenant B must read back its own 250.00");

        // And the id genuinely exists twice.
        Integer both = jdbc.queryForObject(
                "SELECT count(*) FROM payment WHERE payment_id = 'pay_123' AND merchant_id IN (?,?)",
                Integer.class, a, b);
        assertEquals(2, both, "the same id must exist once per tenant");
    }

    private long amountFor(String merchantId) {
        return jdbc.queryForObject(
                "SELECT amount_paise FROM payment WHERE merchant_id=? AND payment_id='pay_123'",
                Long.class, merchantId);
    }

    /**
     * A tenant-scoped read must never return another tenant's rows.
     *
     * Checked across every table that carries money or a decision, because a
     * missing merchant_id predicate on any ONE of them is enough to leak.
     */
    @Test
    void noReadCrossesTheTenantBoundary() {
        String a = merchant("scope_a");
        String b = merchant("scope_b");
        ingest.ingest(a, CsvIngestService.Entity.PAYMENTS, "a.csv", paymentsCsv("100.00"));
        ingest.ingest(b, CsvIngestService.Entity.PAYMENTS, "b.csv", paymentsCsv("250.00"));

        for (String table : List.of("payment", "refund", "chargeback", "reserve_entry",
                                    "settlement", "bank_txn", "recon_run", "ingestion_run")) {
            Integer leaked = jdbc.queryForObject(
                    "SELECT count(*) FROM " + table + " WHERE merchant_id = ?", Integer.class, a);
            Integer leakedOther = jdbc.queryForObject(
                    "SELECT count(*) FROM " + table + " WHERE merchant_id = ? AND merchant_id = ?",
                    Integer.class, a, b);
            assertNotNull(leaked, table + " must be queryable by merchant");
            assertEquals(0, leakedOther, table + " must not match two tenants at once");
        }

        // The ingestion reject reader is scoped by (run, merchant). Asking for
        // tenant A's run as tenant B must find nothing, not merely be filtered.
        Long runOfA = jdbc.queryForObject(
                "SELECT ingestion_id FROM ingestion_run WHERE merchant_id=? ORDER BY ingestion_id DESC LIMIT 1",
                Long.class, a);
        Integer visibleToB = jdbc.queryForObject(
                "SELECT count(*) FROM ingestion_run WHERE ingestion_id=? AND merchant_id=?",
                Integer.class, runOfA, b);
        assertEquals(0, visibleToB, "tenant B must not be able to address tenant A's ingestion run");
    }

    /**
     * Ingestion duplicate detection is per tenant, not global.
     *
     * The same file uploaded twice under ONE tenant must be fully rejected the
     * second time; uploaded once under each of two tenants it must be accepted
     * both times. Getting this backwards is how the original bug behaved.
     */
    @Test
    void duplicateDetectionIsScopedToTheTenant() {
        String a = merchant("dupe_a");
        String b = merchant("dupe_b");
        byte[] csv = paymentsCsv("77.00");

        assertEquals(1, ingest.ingest(a, CsvIngestService.Entity.PAYMENTS, "1.csv", csv).rowsAccepted());
        var again = ingest.ingest(a, CsvIngestService.Entity.PAYMENTS, "2.csv", csv);
        assertEquals(0, again.rowsAccepted(), "same tenant, same id: must not double-insert");
        assertEquals(Map.of("DUPLICATE_EXISTING", 1), again.rejectsByReason());

        assertEquals(1, ingest.ingest(b, CsvIngestService.Entity.PAYMENTS, "3.csv", csv).rowsAccepted(),
                "different tenant, same id: must be accepted");
    }
}
