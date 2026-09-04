package com.settleiq.api;

import com.settleiq.api.repo.PostgresAuditLedger;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Tamper evidence, tested by actually tampering.
 *
 * "The ledger is hash-chained" is a claim about what happens when someone
 * changes a row, so the only test that means anything is one that changes a
 * row. Each case here mutates committed data through a privileged path and
 * asserts the server-side chain walk reports the break.
 *
 * The triggers make honest tampering impossible from the application, which is
 * the guarantee -- so these tests disable the trigger for the duration of the
 * mutation, exactly as a DBA with superuser rights could. That is the threat
 * model: not "can the app corrupt the ledger" (it cannot) but "if someone with
 * database access does, is it detectable afterwards".
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.NONE)
class TamperEvidenceIT {

    @DynamicPropertySource
    static void props(DynamicPropertyRegistry r) { PostgresIT.configure(r); }

    @Autowired JdbcTemplate jdbc;

    private static final String RUN = UUID.randomUUID().toString().substring(0, 8);

    /** A fresh merchant with a short, valid chain to attack. */
    private String seededChain(String base, int rows) {
        String m = base + "_" + RUN;
        jdbc.update("INSERT INTO merchant (merchant_id, name, rate_card) "
                + "VALUES (?,?,'{}'::jsonb) ON CONFLICT DO NOTHING", m, "Test " + m);
        var ledger = new PostgresAuditLedger(jdbc, m, null);
        for (int i = 0; i < rows; i++)
            ledger.append("engine", "auto_post", "settlement", "setl_" + i,
                    "idem_" + m + "_" + i, "model-test-1", 0.99, "AUTO_POST",
                    java.util.Map.of("amount_paise", String.valueOf(1000 + i)));
        assertTrue(PostgresAuditLedger.verify(jdbc, m).isEmpty(), "chain must start valid");
        return m;
    }

    /** Runs a mutation with the append-only triggers momentarily disabled. */
    private void asPrivilegedDba(Runnable mutation) {
        jdbc.execute("ALTER TABLE audit_ledger DISABLE TRIGGER USER");
        try { mutation.run(); }
        finally { jdbc.execute("ALTER TABLE audit_ledger ENABLE TRIGGER USER"); }
    }

    private int breaks(String merchant) {
        return PostgresAuditLedger.verify(jdbc, merchant).size();
    }

    @Test
    void modifyingAPayloadBreaksTheChain() {
        String m = seededChain("tamper_payload", 5);
        asPrivilegedDba(() -> jdbc.update(
                "UPDATE audit_ledger SET payload = '{\"decision\":\"AUTO_POST\",\"amount_paise\":999999}'::jsonb "
                + "WHERE merchant_id = ? AND seq = (SELECT min(seq)+2 FROM audit_ledger WHERE merchant_id = ?)",
                m, m));
        assertTrue(breaks(m) > 0, "an altered payload must be detected");
    }

    @Test
    void modifyingAPreviousHashBreaksTheChain() {
        String m = seededChain("tamper_prev", 5);
        asPrivilegedDba(() -> jdbc.update(
                "UPDATE audit_ledger SET prev_hash = repeat('0', 64) "
                + "WHERE merchant_id = ? AND seq = (SELECT max(seq) FROM audit_ledger WHERE merchant_id = ?)",
                m, m));
        assertTrue(breaks(m) > 0, "a rewritten prev_hash must be detected");
    }

    @Test
    void deletingARowBreaksTheChain() {
        String m = seededChain("tamper_delete", 5);
        asPrivilegedDba(() -> jdbc.update(
                "DELETE FROM audit_ledger WHERE merchant_id = ? "
                + "AND seq = (SELECT min(seq)+2 FROM audit_ledger WHERE merchant_id = ?)", m, m));
        assertTrue(breaks(m) > 0, "a removed row must leave a detectable gap");
    }

    @Test
    void insertingAForgedRowBreaksTheChain() {
        String m = seededChain("tamper_insert", 5);
        // The forged hash must be unique per run: `hash` carries a UNIQUE
        // constraint, so a hardcoded literal passes on a fresh database and
        // collides on the second run against a persistent one. That made the
        // test pass or fail depending on whether the volume had been wiped.
        String forgedHash = String.format("%-64s", "forged" + RUN).replace(' ', 'f');
        asPrivilegedDba(() -> jdbc.update("""
                INSERT INTO audit_ledger (merchant_id, actor, action, entity_type, entity_id,
                                          idempotency_key, model_version, score, verdict,
                                          payload, prev_hash, hash)
                VALUES (?, 'engine', 'auto_post', 'settlement', 'setl_forged', ?,
                        'model-test-1', 0.99, 'AUTO_POST', '{"forged":"1"}'::jsonb,
                        repeat('a', 64), ?)
                """, m, "idem_forged_" + m, forgedHash));
        assertTrue(breaks(m) > 0, "a forged row must not verify");
    }

    @Test
    void reorderingRowsBreaksTheChain() {
        String m = seededChain("tamper_reorder", 5);
        // Swap two payloads: every row still exists, the sequence is intact,
        // only the CONTENTS are transposed. A chain that hashes payloads
        // detects this; one that only counts rows does not.
        asPrivilegedDba(() -> jdbc.update("""
                UPDATE audit_ledger a
                   SET payload = b.payload
                  FROM audit_ledger b
                 WHERE a.merchant_id = ? AND b.merchant_id = a.merchant_id
                   AND a.seq = (SELECT min(seq)   FROM audit_ledger WHERE merchant_id = ?)
                   AND b.seq = (SELECT min(seq)+1 FROM audit_ledger WHERE merchant_id = ?)
                """, m, m, m));
        assertTrue(breaks(m) > 0, "transposed payloads must be detected");
    }

    @Test
    void anUntamperedChainStillVerifies() {
        // The control. Without it, a verifier that always reports a break would
        // pass every test above.
        String m = seededChain("tamper_control", 6);
        assertEquals(0, breaks(m), "an untouched chain must verify clean");
    }

    @Test
    void theApplicationItselfCannotUpdateOrDelete() {
        String m = seededChain("tamper_trigger", 3);
        assertThrows(Exception.class,
                () -> jdbc.update("UPDATE audit_ledger SET payload='{}'::jsonb WHERE merchant_id=?", m),
                "UPDATE must be refused by trigger");
        assertThrows(Exception.class,
                () -> jdbc.update("DELETE FROM audit_ledger WHERE merchant_id=?", m),
                "DELETE must be refused by trigger");
        assertEquals(0, breaks(m), "refused mutations must leave the chain intact");
    }
}
