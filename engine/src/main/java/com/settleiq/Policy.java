package com.settleiq;

import java.time.LocalDate;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Phase 4, node 4 -- the policy check. Deterministic Java. No model output ever
 * reaches this decision except as a NUMBER that was itself produced by
 * calibrated code; the verdict logic is pure arithmetic and set membership.
 *
 * Auto-post requires ALL of:
 *   confidence >= 0.95
 *   AND residue <= Rs 100 OR <= 0.1% of batch value
 *   AND exception type is in the auto-post allowlist
 *   AND no duplicate posting for this idempotency key
 *   AND the daily auto-post budget is not exhausted
 *   AND no ambiguity flag from Stage 4
 *
 * Otherwise: ESCALATE, with a written reason naming the failing arm.
 * Escalation is a SUCCESS state. A system that auto-posts an ambiguous item to
 * keep its automation rate up is worse than one that refuses.
 */
public final class Policy {

    public enum Verdict { AUTO_POST, ESCALATE, SUPPRESSED_DUPLICATE }

    public record Decision(Verdict verdict, String reason, List<String> failedArms,
                           String idempotencyKey) {}

    private final Config cfg;
    private final AuditSink audit;
    private final Map<LocalDate, Long> spentToday = new HashMap<>();
    private final Map<LocalDate, Integer> countToday = new HashMap<>();

    public Policy(Config cfg, AuditSink audit) { this.cfg = cfg; this.audit = audit; }

    public Decision evaluate(String merchantId, String settlementId, String exceptionType,
                             double confidence, long residuePaise, long batchValuePaise,
                             boolean ambiguous, LocalDate valueDate, String evidenceDigest) {

        String idem = Audit.key(merchantId, settlementId, "resolve_exception",
                exceptionType, Long.toString(residuePaise), evidenceDigest);

        if (audit.seen(idem))
            return new Decision(Verdict.SUPPRESSED_DUPLICATE,
                    "identical posting already exists in the ledger; nothing written",
                    List.of("duplicate_idempotency_key"), idem);

        List<String> failed = new ArrayList<>();

        if (confidence < cfg.autoPostMinConfidence)
            failed.add(String.format("confidence %.4f < %.2f", confidence, cfg.autoPostMinConfidence));

        long absResidue = Math.abs(residuePaise);
        boolean residueOk = absResidue <= cfg.autoPostMaxResiduePaise
                || (batchValuePaise > 0
                    && absResidue <= Math.round(batchValuePaise * cfg.autoPostMaxResidueFraction));
        if (!residueOk)
            failed.add("residue " + Money.fmt(absResidue) + " exceeds both Rs "
                    + Money.fmt(cfg.autoPostMaxResiduePaise) + " and "
                    + (cfg.autoPostMaxResidueFraction * 100) + "% of batch value "
                    + Money.fmt(batchValuePaise));

        if (!cfg.autoPostAllowlist.contains(exceptionType))
            failed.add("exception type '" + exceptionType + "' is not on the auto-post allowlist");

        if (ambiguous)
            failed.add("Stage 4 raised an ambiguity flag; assignment is not determined by arithmetic");

        long spent = spentToday.getOrDefault(valueDate, 0L);
        int cnt = countToday.getOrDefault(valueDate, 0);
        if (spent + absResidue > cfg.dailyAutoPostBudgetPaise)
            failed.add("daily auto-post value budget exhausted (" + Money.fmt(spent) + " of "
                    + Money.fmt(cfg.dailyAutoPostBudgetPaise) + " used on " + valueDate + ")");
        if (cnt + 1 > cfg.dailyAutoPostCountBudget)
            failed.add("daily auto-post count budget exhausted (" + cnt + " of "
                    + cfg.dailyAutoPostCountBudget + " used on " + valueDate + ")");

        if (failed.isEmpty()) {
            spentToday.put(valueDate, spent + absResidue);
            countToday.put(valueDate, cnt + 1);
            return new Decision(Verdict.AUTO_POST,
                    "all six policy arms satisfied; journal entry posted automatically",
                    List.of(), idem);
        }
        return new Decision(Verdict.ESCALATE,
                "held for human review: " + String.join("; ", failed), failed, idem);
    }
}
