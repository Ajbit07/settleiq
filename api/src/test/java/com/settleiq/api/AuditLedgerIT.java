package com.settleiq.api;

import com.settleiq.api.repo.PostgresAuditLedger;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

/**
 * The audit ledger's three guarantees, tested against real Postgres 16.
 *
 * These are database behaviours, not application behaviours: an in-memory
 * substitute would pass without exercising the trigger, the unique index or the
 * plpgsql chain walk, which is exactly where the guarantees actually live.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.NONE)
class AuditLedgerIT {

    @DynamicPropertySource
    static void props(DynamicPropertyRegistry r) { PostgresIT.configure(r); }

    @Autowired JdbcTemplate jdbc;

    /**
     * A fresh identity per JVM run.
     *
     * These tests cannot tidy up after themselves: audit_ledger refuses DELETE
     * and TRUNCATE by trigger, which is the property under test. So instead of
     * cleaning state they use state that has never existed before. That the
     * cleanup is impossible is itself the guarantee working.
     */
    private static final String RUN = java.util.UUID.randomUUID().toString().substring(0, 8);

    private String merchant(String base) {
        String id = base + "_" + RUN;
        jdbc.update("""
                INSERT INTO merchant (merchant_id, name, rate_card)
                VALUES (?, ?, '{}'::jsonb) ON CONFLICT DO NOTHING
                """, id, "Test " + id);
        return id;
    }

    @Test
    void appendsChainAndVerifies() {
        String m = merchant("m_chain");
        var led = new PostgresAuditLedger(jdbc, m, null);
        // Keys are globally unique, not per-merchant: a fresh merchant is not
        // enough, the key itself has to be new or the ledger rightly refuses it.
        assertTrue(led.append("engine", "auto_post", "settlement", "s1", "k1-" + RUN,
                "v1", 0.99, "AUTO_POST", Map.of("residue", "10.00")));
        assertTrue(led.append("agent", "escalate", "settlement", "s2", "k2-" + RUN,
                "v1", 0.80, "ESCALATE", Map.of("residue", "200.00")));
        assertEquals(2, led.appendedCount());
        assertTrue(PostgresAuditLedger.verify(jdbc, m).isEmpty(),
                "a freshly written chain must verify");
    }

    @Test
    void duplicateIdempotencyKeyIsSuppressedNotDoublePosted() {
        String m = merchant("m_idem");
        String key = "dup-key-" + RUN;
        var first = new PostgresAuditLedger(jdbc, m, null);
        assertTrue(first.append("engine", "auto_post", "settlement", "s1", key,
                "v1", 0.99, "AUTO_POST", Map.of("residue", "10.00")));

        // A retried run builds a fresh sink, exactly as the worker would.
        var retry = new PostgresAuditLedger(jdbc, m, null);
        assertFalse(retry.append("engine", "auto_post", "settlement", "s1", key,
                "v1", 0.99, "AUTO_POST", Map.of("residue", "10.00")),
                "the second posting of an identical key must be refused");
        assertEquals(1, retry.suppressedDuplicates());

        Integer rows = jdbc.queryForObject(
                "SELECT count(*) FROM audit_ledger WHERE merchant_id=?", Integer.class, m);
        assertEquals(1, rows, "a retry must not create a second row");
    }

    @Test
    void ledgerRejectsUpdateAndDelete() {
        String m = merchant("m_immutable");
        var led = new PostgresAuditLedger(jdbc, m, null);
        led.append("engine", "auto_post", "settlement", "s1", "imm-" + RUN,
                "v1", 0.99, "AUTO_POST", Map.of("residue", "1.00"));

        var onUpdate = assertThrows(Exception.class, () -> jdbc.update(
                "UPDATE audit_ledger SET verdict='TAMPERED' WHERE merchant_id='" + m + "'"));
        assertTrue(onUpdate.getMessage().contains("append-only"), onUpdate.getMessage());

        var onDelete = assertThrows(Exception.class, () -> jdbc.update(
                "DELETE FROM audit_ledger WHERE merchant_id='" + m + "'"));
        assertTrue(onDelete.getMessage().contains("append-only"), onDelete.getMessage());
    }

    @Test
    void chainWalkDetectsTampering() {
        String m = merchant("m_tamper");
        var led = new PostgresAuditLedger(jdbc, m, null);
        for (int i = 0; i < 4; i++)
            led.append("engine", "auto_post", "settlement", "s" + i, "t-" + RUN + "-" + i,
                    "v1", 0.99, "AUTO_POST", Map.of("residue", "1.00"));
        assertTrue(PostgresAuditLedger.verify(jdbc, m).isEmpty());

        // The trigger blocks UPDATE, so simulate a privileged tamper the only
        // way it could really happen: disable the trigger, alter a link, restore.
        jdbc.execute("ALTER TABLE audit_ledger DISABLE TRIGGER audit_ledger_no_update");
        jdbc.update("""
                UPDATE audit_ledger SET prev_hash = repeat('f',64)
                 WHERE merchant_id = ?
                   AND seq = (SELECT min(seq)+2 FROM audit_ledger WHERE merchant_id = ?)
                """, m, m);
        jdbc.execute("ALTER TABLE audit_ledger ENABLE TRIGGER audit_ledger_no_update");

        var breaks = PostgresAuditLedger.verify(jdbc, m);
        assertFalse(breaks.isEmpty(), "a broken link must be detected");
        assertTrue(breaks.get(0).reason().contains("prev_hash"));
    }
}
