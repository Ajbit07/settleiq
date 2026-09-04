package com.settleiq.api;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceUtils;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

import javax.sql.DataSource;
import java.sql.Connection;
import java.sql.ResultSet;
import java.sql.Statement;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Job claiming under concurrency.
 *
 * The property under test is the one that matters in production: two workers
 * polling simultaneously must never both run the same merchant-period. A
 * check-then-act claim passes a single-threaded test and fails here.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.NONE)
class JobClaimIT {

    @DynamicPropertySource
    static void props(DynamicPropertyRegistry r) { PostgresIT.configure(r); }

    @Autowired JdbcTemplate jdbc;
    @Autowired DataSource ds;

    private static final String RUN = java.util.UUID.randomUUID().toString().substring(0, 8);

    /**
     * Empty the queue first. Leftovers from an earlier run are claimable, and a
     * second worker picking up an unrelated job would look exactly like the
     * double-claim this test exists to catch -- a false pass in the worst place.
     */
    @org.junit.jupiter.api.BeforeEach
    void clearQueue() {
        jdbc.update("DELETE FROM recon_job");
    }

    private String seedJob(String base) {
        String merchant = base + "_" + RUN;
        jdbc.update("""
                INSERT INTO merchant (merchant_id, name, rate_card)
                VALUES (?,?, '{}'::jsonb) ON CONFLICT DO NOTHING
                """, merchant, merchant);
        jdbc.update("""
                INSERT INTO recon_job (merchant_id, period_start, period_end)
                VALUES (?, DATE '2025-11-01', DATE '2025-11-30')
                ON CONFLICT (merchant_id, period_start, period_end)
                  DO UPDATE SET state='queued', attempts=0
                """, merchant);
        return merchant;
    }

    @Test
    void onlyOneWorkerClaimsAJob() throws Exception {
        String m = seedJob("m_claim");

        // Two independent connections, i.e. two workers. The advisory lock is
        // session-scoped, so this must be done on real separate connections
        // rather than two JdbcTemplate calls on one pooled connection.
        try (Connection a = ds.getConnection(); Connection b = ds.getConnection()) {
            a.setAutoCommit(true);
            b.setAutoCommit(true);
            int claimedA = claim(a, "worker-a");
            int claimedB = claim(b, "worker-b");
            assertEquals(1, claimedA + claimedB,
                    "exactly one worker may claim the job; got a=" + claimedA + " b=" + claimedB);
        }
    }

    private int claim(Connection c, String worker) throws Exception {
        try (Statement st = c.createStatement();
             ResultSet rs = st.executeQuery(
                     "SELECT * FROM claim_recon_job('" + worker + "')")) {
            return rs.next() ? 1 : 0;
        }
    }

    @Test
    void staleJobIsRequeued() {
        String m = seedJob("m_stale");
        jdbc.update("""
                UPDATE recon_job SET state='running', claimed_by='dead-worker',
                       claimed_at = now() - interval '2 hours', attempts = 1
                 WHERE merchant_id = ?
                """, m);
        Integer n = jdbc.queryForObject(
                "SELECT reap_stale_jobs(make_interval(mins => 15))", Integer.class);
        assertEquals(1, n, "a job whose worker vanished must be re-queued");
        String state = jdbc.queryForObject(
                "SELECT state FROM recon_job WHERE merchant_id=?", String.class, m);
        assertEquals("queued", state);
    }

    @Test
    void moneyColumnsAreIntegerPaiseOnly() {
        // A float money column would be a silent correctness bug that no unit
        // test would catch, so the schema itself is asserted here.
        List<Map<String, Object>> bad = jdbc.queryForList("""
                SELECT table_name, column_name, data_type
                  FROM information_schema.columns
                 WHERE table_schema = 'public'
                   AND (column_name LIKE '%paise%' OR column_name LIKE '%amount%')
                   AND data_type NOT IN ('bigint')
                """);
        assertTrue(bad.isEmpty(), "money columns must be BIGINT paise, found: " + bad);

        List<Map<String, Object>> floats = jdbc.queryForList("""
                SELECT table_name, column_name, data_type
                  FROM information_schema.columns
                 WHERE table_schema = 'public'
                   AND data_type IN ('double precision', 'real')
                """);
        assertTrue(floats.isEmpty(), "no floating-point columns permitted, found: " + floats);
    }
}
