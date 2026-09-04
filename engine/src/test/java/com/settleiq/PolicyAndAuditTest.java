package com.settleiq;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Policy is the gate between a model's opinion and a posted journal entry, so
 * each arm is tested in isolation. Every one of these is a case where the
 * system must decline.
 */
class PolicyAndAuditTest {

    private Policy policy(Path dir, Config cfg) {
        return new Policy(cfg, new Audit(dir.resolve("ledger.jsonl")));
    }

    private static final LocalDate D = LocalDate.of(2025, 11, 20);

    @Test
    void autoPostsOnlyWhenEveryArmPasses(@TempDir Path dir) {
        var d = policy(dir, new Config()).evaluate("m1", "s1", "fee_variance",
                0.99, -5000L, 5_00_000L, false, D, "ev1");
        assertEquals(Policy.Verdict.AUTO_POST, d.verdict());
        assertTrue(d.failedArms().isEmpty());
    }

    @Test
    void lowConfidenceEscalates(@TempDir Path dir) {
        var d = policy(dir, new Config()).evaluate("m1", "s1", "fee_variance",
                0.90, -5000L, 5_00_000L, false, D, "ev1");
        assertEquals(Policy.Verdict.ESCALATE, d.verdict());
        assertTrue(d.failedArms().stream().anyMatch(a -> a.contains("confidence")));
    }

    @Test
    void ambiguityAloneBlocksAutoPost(@TempDir Path dir) {
        // Everything else is perfect. Ambiguity must still stop it: the whole
        // point of the swap-invariance detector is that it can veto.
        var d = policy(dir, new Config()).evaluate("m1", "s1", "fee_variance",
                1.00, 0L, 5_00_000L, true, D, "ev1");
        assertEquals(Policy.Verdict.ESCALATE, d.verdict());
        assertTrue(d.failedArms().stream().anyMatch(a -> a.contains("ambiguity")));
    }

    @Test
    void typeOutsideAllowlistEscalates(@TempDir Path dir) {
        var d = policy(dir, new Config()).evaluate("m1", "s1", "unexplained",
                0.99, -50L, 5_00_000L, false, D, "ev1");
        assertEquals(Policy.Verdict.ESCALATE, d.verdict());
        assertTrue(d.failedArms().stream().anyMatch(a -> a.contains("allowlist")));
    }

    @Test
    void largeResiduePassesOnFractionArmButNotOnAbsolute(@TempDir Path dir) {
        Config cfg = new Config();
        // Rs 400 residue: over the Rs 100 cap, but under 0.1% of a Rs 50L batch.
        var ok = policy(dir, cfg).evaluate("m1", "s1", "fee_variance",
                0.99, -40000L, 50_000_00L * 10, false, D, "ev1");
        assertEquals(Policy.Verdict.AUTO_POST, ok.verdict());

        // Same residue on a small batch fails both arms.
        var bad = policy(dir, cfg).evaluate("m1", "s2", "fee_variance",
                0.99, -40000L, 1_00_000L, false, D, "ev2");
        assertEquals(Policy.Verdict.ESCALATE, bad.verdict());
        assertTrue(bad.failedArms().stream().anyMatch(a -> a.contains("residue")));
    }

    @Test
    void dailyBudgetExhaustionStopsFurtherAutoPosts(@TempDir Path dir) {
        Config cfg = new Config();
        cfg.dailyAutoPostCountBudget = 2;
        Policy p = policy(dir, cfg);
        assertEquals(Policy.Verdict.AUTO_POST,
                p.evaluate("m", "s1", "fee_variance", 0.99, -10L, 1_00_000L, false, D, "a").verdict());
        assertEquals(Policy.Verdict.AUTO_POST,
                p.evaluate("m", "s2", "fee_variance", 0.99, -10L, 1_00_000L, false, D, "b").verdict());
        var third = p.evaluate("m", "s3", "fee_variance", 0.99, -10L, 1_00_000L, false, D, "c");
        assertEquals(Policy.Verdict.ESCALATE, third.verdict());
        assertTrue(third.failedArms().stream().anyMatch(a -> a.contains("count budget")));
    }

    // ------------------------------------------------------------- audit
    @Test
    void rerunAppendsNothingAndChainStaysValid(@TempDir Path dir) throws Exception {
        Path led = dir.resolve("ledger.jsonl");
        Audit a = new Audit(led);
        for (int i = 0; i < 5; i++)
            a.append("engine", "auto_post", "settlement", "s" + i, "key-" + i,
                    "v1", 0.99, "AUTO_POST", Map.of("residue", "1.00"));
        assertEquals(5, Files.readAllLines(led).size());
        assertTrue(Audit.verify(led).valid());

        // A retry re-derives the same keys from the same facts.
        Audit retry = new Audit(led);
        for (int i = 0; i < 5; i++)
            assertFalse(retry.append("engine", "auto_post", "settlement", "s" + i, "key-" + i,
                    "v1", 0.99, "AUTO_POST", Map.of("residue", "1.00")));
        assertEquals(5, Files.readAllLines(led).size(), "a retry must append nothing");
        assertEquals(5, retry.suppressedDuplicates());
        assertTrue(Audit.verify(led).valid());
    }

    @Test
    void alteringOneByteBreaksTheChain(@TempDir Path dir) throws Exception {
        Path led = dir.resolve("ledger.jsonl");
        Audit a = new Audit(led);
        for (int i = 0; i < 4; i++)
            a.append("engine", "auto_post", "settlement", "s" + i, "k" + i,
                    "v1", 0.99, "AUTO_POST", Map.of("residue", "1.00"));
        assertTrue(Audit.verify(led).valid());

        List<String> lines = Files.readAllLines(led);
        lines.set(1, lines.get(1).replace("\"residue\":\"1.00\"", "\"residue\":\"9.00\""));
        Files.write(led, lines);

        var v = Audit.verify(led);
        assertFalse(v.valid(), "a single altered byte must break verification");
        assertNotNull(v.failureAt());
    }
}
