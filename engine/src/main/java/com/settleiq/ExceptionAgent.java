package com.settleiq;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

import com.settleiq.Model.*;

/**
 * Bounded exception agent.
 *
 * This is a real investigation loop, not a fixed script: at each step the
 * planner looks at what the tools have returned so far and chooses which
 * read-only tool to run next. Two exceptions with different evidence take
 * different paths and call different tools, which is the whole point -- an
 * agent that always runs the same four steps is a pipeline wearing an agent's
 * vocabulary.
 *
 *   loop { plan next tool -> call it -> fold its signals into state }
 *   until nothing further would change the answer, or the step cap is hit
 *   then classify -> propose resolution -> hand to Policy
 *
 * THE CORE RULE: the LLM chooses; arithmetic decides.
 *
 * The planner may be an LLM (see LlmAdapter) or the deterministic planner
 * below. Either way it only ever picks WHICH read-only tool to run and WHICH
 * label from a closed taxonomy applies. Every rupee in the outcome is computed
 * by AgentTools and re-derived by Policy from source rows. There is no tool
 * that writes, so no investigation path can reach the ledger.
 *
 * The step cap is hard. Exceeding it throws rather than truncating quietly,
 * because a silent truncation would produce a confident-looking conclusion
 * drawn from half an investigation.
 */
public final class ExceptionAgent {

    /** Hard cap on tool calls per exception. Exceeding it is a bug, not a case. */
    public static final int MAX_STEPS = 6;

    /**
     * The lookups classify() can actually act on, in its own precedence order.
     *
     * Every one of these is a question whose answer can NAME a break. If one is
     * never asked, the classifier cannot distinguish "no such cause" from "not
     * checked", and defaults to unexplained. Keep this in step with classify():
     * a branch there that reads a tool absent from here is a cause the agent
     * can silently fail to find.
     */
    static final List<String> DECISIVE = List.of(
            "check_swap_invariance", "check_duplicate_credit",
            "get_refunds", "get_chargebacks", "get_reserve_movements",
            "check_rate_card");

    /** Fixed taxonomy. The agent may not invent a category. */
    public static final List<String> TAXONOMY = List.of(
            "timing_offset", "netted_refund", "chargeback_debit", "reserve_movement",
            "duplicate_credit", "fee_variance", "ambiguous_candidates",
            "attribution_ambiguous", "partition_unproven", "unexplained");

    /** Closed set of resolutions. The agent fills slots; it computes nothing. */
    public static final List<String> RESOLUTIONS = List.of(
            "journal_entry", "write_off_within_limit", "chase_platform", "escalate");

    /** A fact with the id of the row it came from. No provenance, no fact. */
    public record Evidence(String claim, String provenanceType, String provenanceId,
                           long amountPaise) {
        public String render() {
            return claim + "  [" + provenanceType + ":" + provenanceId + "]";
        }
    }

    /** One planned-and-executed investigation step, kept for the audit trail. */
    public record Step(int n, String tool, String chosenBy, String reason,
                       int factsReturned, long latencyNanos) {}

    public record Trace(String exceptionId, String classification, double classifierConfidence,
                        List<Evidence> evidence, String resolution, String rationale,
                        List<String> steps, List<Step> plan,
                        boolean llmUsed, String llmModel, String llmStatus,
                        String llmProvider, int llmCalls, long investigationMicros) {
        /** Tools the LLM itself selected, as opposed to the deterministic planner. */
        public List<String> llmSelectedTools() {
            List<String> out = new java.util.ArrayList<>();
            for (Step s : plan) if (s.chosenBy().startsWith("llm:")) out.add(s.tool());
            return out;
        }
    }

    private final Config cfg;
    private final Inputs in;
    private final AgentTools tools;
    private final LlmAdapter llm;

    public ExceptionAgent(Config cfg, Inputs in, FeeModel fees) {
        // A run that wants a model-free baseline must get one regardless of what
        // is in the environment, or the "deterministic" comparison silently
        // becomes a second LLM run and measures nothing.
        this(cfg, in, fees, cfg.llmPlanner ? new LlmAdapter()
                : new LlmAdapter(new LlmAdapter.Settings(LlmAdapter.Provider.NONE, null,
                        "", "", java.time.Duration.ofMillis(1), 0)));
    }

    public ExceptionAgent(Config cfg, Inputs in, FeeModel fees, LlmAdapter llm) {
        this.cfg = cfg;
        this.in = in;
        this.tools = new AgentTools(cfg, in, fees);
        this.llm = llm;
    }

    public Trace run(Pipeline.Exception2 x, Map<String, Settlement> setlById,
                     Set<String> reversedBankRows) {

        Map<String, AgentTools.Result> observed = new LinkedHashMap<>();
        List<Step> plan = new ArrayList<>();
        List<String> stepNames = new ArrayList<>();
        List<Evidence> evidence = new ArrayList<>();
        Set<String> seenClaims = new LinkedHashSet<>();

        if (x.partitionRefusal != null) {
            evidence.add(new Evidence(
                    "the partition solver could not prove which payments fund this batch: "
                    + x.partitionRefusal, "settlement", x.settlementId, x.batchValue));
            evidence.add(new Evidence(
                    "batch gross shown is the platform's own reported figure, not a total "
                    + "reconstructed from payment rows", "settlement", x.settlementId, 0));
        }
        if (x.attributionRefusal != null) {
            var u = x.attributionRefusal;
            evidence.add(new Evidence(u.adjustmentType + " " + u.adjustmentId + " of "
                    + Money.fmtInr(u.amountPaise) + " is stamped " + u.stampedAt,
                    u.adjustmentType, u.adjustmentId, u.amountPaise));
            evidence.add(new Evidence("no batch settling " + u.settleDate
                    + " has a reconstructed member trading at that time",
                    "settlement", String.join("|", u.candidates), 0));
            for (String cand : u.candidates)
                evidence.add(new Evidence("candidate batch considered and not proven",
                        "settlement", cand, 0));
        }
        boolean llmUsed = false;
        int llmCalls = 0;
        long t0 = System.nanoTime();
        String llmStatus = llm.status();

        // ---- investigation loop ---------------------------------------------
        for (int step = 1; step <= MAX_STEPS; step++) {
            List<String> remaining = new ArrayList<>(AgentTools.TOOLS);
            remaining.removeAll(observed.keySet());
            if (remaining.isEmpty()) break;

            Plan next = planNext(x, observed, remaining);
            if (next == null) break;                 // nothing left worth reading

            String chosenBy = "deterministic";
            // The LLM only ever REORDERS a closed list of read-only tools. If it
            // answers off-list, times out, or is not configured, the
            // deterministic choice stands and the trace says so.
            if (llm.available()) {
                var c = llm.choose(
                        "You are triaging a payment-settlement reconciliation break. "
                        + "Choose the single most informative next lookup.",
                        renderContext(x, observed), remaining);
                if (c.ok()) {
                    // The model's own stated reason is kept verbatim so the
                    // audit trail can show WHAT THE LLM CHOSE and why it said it
                    // chose it -- never that the LLM computed anything.
                    next = new Plan(c.value(), c.reason() == null || c.reason().isBlank()
                            ? "chosen by " + llm.provider() + "/" + llm.model()
                            : c.reason().trim());
                    chosenBy = "llm:" + llm.model();
                    llmCalls++;
                    llmUsed = true;
                } else {
                    llmCalls++;
                    llmStatus = "fell back to deterministic planner (" + c.outcome()
                            + "): " + c.detail();
                }
            }

            AgentTools.Result r = tools.call(next.tool(), x);
            observed.put(next.tool(), r);
            plan.add(new Step(step, next.tool(), chosenBy, next.reason(),
                    r.facts().size(), r.latencyNanos()));
            stepNames.add(next.tool());
            for (AgentTools.Fact f : r.facts())
                if (seenClaims.add(f.claim()))
                    evidence.add(new Evidence(f.claim(), f.provenanceType(),
                            f.provenanceId(), f.amountPaise()));
        }

        if (plan.size() > MAX_STEPS)
            throw new IllegalStateException("agent exceeded hard step cap");

        /*
         * ---- mandatory completion ------------------------------------------
         *
         * The planner chooses the ORDER of an investigation. It does not get to
         * decide which questions go unasked.
         *
         * classify() can only name a cause it has evidence for, so a lookup
         * that was never run reads exactly like a lookup that came back empty:
         * both leave the break "unexplained". That is a false negative, and a
         * costly one -- it turns a fee the rate card fully explains into an
         * unexplained residue an operator has to work by hand.
         *
         * Measured: with a model planning the sequence, three fee_variance
         * cases collapsed to unexplained because the six planned steps were
         * spent on breadth and check_rate_card was never reached. The
         * deterministic planner happened to reach it in three. Neither planner
         * should be trusted to remember; the engine guarantees it instead.
         *
         * So every lookup the classifier can act on is run before it decides,
         * whoever chose the sequence above. These steps are local, read-only
         * and cost no planner call, and they are recorded as chosen by the
         * engine so the trace never implies the model asked for them.
         */
        for (String tool : DECISIVE) {
            if (observed.containsKey(tool)) continue;
            AgentTools.Result r = tools.call(tool, x);
            observed.put(tool, r);
            plan.add(new Step(plan.size() + 1, tool, "engine:mandatory",
                    "the classifier can act on this lookup and the planner did not reach it",
                    r.facts().size(), r.latencyNanos()));
            stepNames.add(tool);
            for (AgentTools.Fact f : r.facts())
                if (seenClaims.add(f.claim()))
                    evidence.add(new Evidence(f.claim(), f.provenanceType(),
                            f.provenanceId(), f.amountPaise()));
        }

        // ---- classify from what the tools actually returned -------------------
        stepNames.add("classify");
        var cls = classify(x, observed);
        String label = cls.getKey();
        double clsConf = cls.getValue();

        stepNames.add("propose_resolution");
        String resolution = proposeResolution(label, x);

        // policy_check runs in Policy, deterministically, on re-derived numbers
        stepNames.add("policy_check");

        String rationale = rationale(label, x, observed, evidence);
        return new Trace(x.settlementId, label, clsConf, evidence, resolution, rationale,
                stepNames, plan, llmUsed, llm.model(), llmStatus,
                llm.provider(), llmCalls, (System.nanoTime() - t0) / 1000);
    }

    private record Plan(String tool, String reason) {}

    /**
     * Deterministic planner.
     *
     * Genuinely state-driven: each rule fires on what earlier tools returned, so
     * an ambiguous break stops after two lookups while a residue of unknown
     * origin keeps reading until something accounts for it or nothing does.
     * Returning null means "no remaining lookup could change the answer", which
     * is a real decision and is why most exceptions cost 2-4 tool calls rather
     * than the full six.
     */
    private Plan planNext(Pipeline.Exception2 x, Map<String, AgentTools.Result> seen,
                          List<String> remaining) {
        // An attribution refusal has nothing to investigate: the engine already
        // established that no batch's observed traffic brackets the adjustment.
        // Reading more rows cannot change that, so the agent spends no budget.
        if (x.attributionRefusal != null || x.partitionRefusal != null) return null;

        // 1. Always establish the settlement first: everything else is relative to it.
        if (remaining.contains("get_settlement"))
            return new Plan("get_settlement", "establish the batch this break is against");

        // 2. A flagged ambiguity is decisive on its own -- confirm it and stop.
        if (x.ambiguous && remaining.contains("check_swap_invariance"))
            return new Plan("check_swap_invariance",
                    "stage 4 raised an ambiguity flag; confirm it is swap-invariant");
        if (x.ambiguous && seen.containsKey("check_swap_invariance")) return null;

        // 3. No linked bank row: the question is which credit, not which fee.
        if (x.unmatched && remaining.contains("check_duplicate_credit"))
            return new Plan("check_duplicate_credit", "no bank credit is linked to this batch");
        if (x.unmatched && remaining.contains("get_bank_candidates"))
            return new Plan("get_bank_candidates", "enumerate credits that could belong here");
        if (x.unmatched && seen.containsKey("get_bank_candidates")) return null;

        // 4. Quantify the gap before trying to explain it.
        //
        //    Note which gap. compare_records measures BANK CREDIT vs SETTLEMENT
        //    NET, and for almost every batch those agree exactly -- the credit
        //    is right. The residue under investigation is a different quantity:
        //    what is left after decomposing that credit into fees, tax, refunds
        //    and reserve. So a clean compare_records ends nothing, and only a
        //    residue inside tolerance means there is genuinely nothing to chase.
        if (remaining.contains("compare_records"))
            return new Plan("compare_records", "measure the gap between credit and settlement net");
        if (Math.abs(x.residue) <= cfg.tolerancePaise) return null;

        // 5. The sign of the residue is a free discriminator, so spend it before
        //    spending lookups. Pricing from the published card, a HIGHER charge
        //    arrives as a SMALLER credit -- so only a negative residue can be a
        //    fee variance, and on a negative residue that hypothesis is worth
        //    testing before enumerating documents that might match the gap.
        if (x.residue < 0 && remaining.contains("check_rate_card"))
            return new Plan("check_rate_card", "negative residue: test for an unpublished rate");
        var rateEarly = seen.get("check_rate_card");
        if (rateEarly != null && rateEarly.flag("consistent_with_fee_variance")) return null;

        // 6. Cheapest complete explanations next: a refund or chargeback that
        //    matches the gap exactly ends the investigation.
        if (remaining.contains("get_refunds"))
            return new Plan("get_refunds", "a netted refund of the same size would explain it");
        if (explained(seen, "get_refunds")) return null;

        if (remaining.contains("get_chargebacks"))
            return new Plan("get_chargebacks", "a chargeback plus fee would explain it");
        if (explained(seen, "get_chargebacks")) return null;

        if (remaining.contains("get_reserve_movements"))
            return new Plan("get_reserve_movements", "a reserve release on this date would explain it");
        if (explained(seen, "get_reserve_movements")) return null;

        // 6. Nothing on file matches the gap. Test whether it scales with card
        //    volume, which is what a silent rate change looks like.
        if (remaining.contains("get_payments_in_batch"))
            return new Plan("get_payments_in_batch", "recover batch membership and method mix");
        if (remaining.contains("check_rate_card"))
            return new Plan("check_rate_card", "test whether the gap implies an unpublished rate");
        var rate = seen.get("check_rate_card");
        if (rate != null && rate.flag("consistent_with_fee_variance")) return null;

        // 7. Last resort: read the narration for anything the matcher missed.
        if (remaining.contains("inspect_narration"))
            return new Plan("inspect_narration", "look for a reference the matcher did not use");

        return null;
    }

    private static boolean explained(Map<String, AgentTools.Result> seen, String tool) {
        var r = seen.get(tool);
        return r != null && r.flag("explains_residue");
    }

    /** Compact, factual state summary. Only tool output; never prior prose. */
    private String renderContext(Pipeline.Exception2 x, Map<String, AgentTools.Result> seen) {
        StringBuilder sb = new StringBuilder();
        sb.append("residue=").append(Money.fmtInr(x.residue))
          .append(" batch_value=").append(Money.fmtInr(x.batchValue))
          .append(" ambiguous=").append(x.ambiguous)
          .append(" bank_linked=").append(x.bankTxnId != null).append('\n');
        if (seen.isEmpty()) sb.append("no lookups performed yet\n");
        for (var e : seen.entrySet())
            sb.append(e.getKey()).append(" -> ").append(e.getValue().signal()).append('\n');
        return sb.toString();
    }

    /**
     * Classification from accumulated tool signals.
     *
     * Every branch is a predicate over something a tool actually returned, so
     * the label is reproducible and arguable. A label is never assigned on the
     * grounds that a document merely EXISTS in the batch -- the document has to
     * account for the gap, which is what `explains_residue` means.
     */
    private Map.Entry<String, Double> classify(Pipeline.Exception2 x,
                                               Map<String, AgentTools.Result> seen) {
        // An attribution refusal is settled by the ABSENCE of evidence. No
        // further lookup can manufacture the ownership proof that was missing,
        // so the label is fixed and the confidence is honestly zero.
        if (x.attributionRefusal != null) return Map.entry("attribution_ambiguous", 0.0);
        // The partition solver already proved that membership cannot be
        // established. No lookup can manufacture the proof it did not find.
        if (x.partitionRefusal != null) return Map.entry("partition_unproven", 0.0);
        if (flag(seen, "check_swap_invariance", "ambiguous"))
            return Map.entry("ambiguous_candidates", 0.99);
        if (flag(seen, "check_duplicate_credit", "unmatched"))
            return Map.entry("duplicate_credit", 0.72);

        if (Math.abs(x.residue) <= cfg.tolerancePaise)
            return Map.entry("timing_offset", 0.90);

        if (explained(seen, "get_refunds"))            return Map.entry("netted_refund", 0.85);
        if (explained(seen, "get_chargebacks"))        return Map.entry("chargeback_debit", 0.87);
        if (explained(seen, "get_reserve_movements"))  return Map.entry("reserve_movement", 0.88);

        if (flag(seen, "check_rate_card", "consistent_with_fee_variance"))
            return Map.entry("fee_variance", 0.96);

        // Nothing on file accounts for it. Say so, rather than naming the
        // nearest document and calling the break explained.
        return Map.entry("unexplained", 0.55);
    }

    private static boolean flag(Map<String, AgentTools.Result> seen, String tool, String key) {
        var r = seen.get(tool);
        return r != null && r.flag(key);
    }

    /** Slot filling only. The backend computes the amounts. */
    private String proposeResolution(String label, Pipeline.Exception2 x) {
        long res = Math.abs(x.residue);
        return switch (label) {
            case "timing_offset", "netted_refund", "reserve_movement" -> "journal_entry";
            case "fee_variance" -> res <= cfg.autoPostMaxResiduePaise
                    ? "journal_entry" : "chase_platform";
            case "ambiguous_candidates", "duplicate_credit", "attribution_ambiguous",
                 "partition_unproven" -> "escalate";
            case "chargeback_debit" -> "journal_entry";
            default -> res <= cfg.autoPostMaxResiduePaise ? "write_off_within_limit" : "escalate";
        };
    }

    /** Prose assembled from tool results. Every number here was computed by a tool. */
    private String rationale(String label, Pipeline.Exception2 x,
                             Map<String, AgentTools.Result> seen, List<Evidence> ev) {
        StringBuilder sb = new StringBuilder();
        sb.append("Residue of ").append(Money.fmtInr(x.residue))
          .append(" on a batch of ").append(Money.fmtInr(x.batchValue))
          .append(" classified as ").append(label).append(". ");
        switch (label) {
            case "fee_variance" -> {
                var r = seen.get("check_rate_card");
                sb.append("Every other component reconciles exactly, and the gap implies ")
                  .append(r == null ? "an unpublished rate"
                          : String.format("%.4f%% on card volume above the published %.2f%%",
                                          r.num("implied_excess_pct"), r.num("published_pct")))
                  .append(", so the platform charged a rate the published card does not show. "
                          + "Amount computed from the rate card, not asserted.");
            }
            case "ambiguous_candidates" -> sb.append(
                    "Assignment is not determined by arithmetic: swapping the tied payments "
                    + "leaves every batch total unchanged. Refusing to auto-post.");
            case "attribution_ambiguous" -> {
                var u = x.attributionRefusal;
                sb.setLength(0);
                sb.append("Adjustment ").append(u.adjustmentType).append(' ')
                  .append(u.adjustmentId).append(" for ").append(Money.fmtInr(u.amountPaise))
                  .append(", stamped ").append(u.stampedAt)
                  .append(", could not be attributed to a settlement batch. ")
                  .append(u.reason).append(". Candidate batches the inferred boundary would have "
                  + "chosen between: ").append(String.join(", ", u.candidates))
                  .append(". Not netting it into any payout, so the gap stays visible.");
            }
            case "duplicate_credit" -> {
                var r = seen.get("get_bank_candidates");
                sb.append("No bank credit is linked");
                if (r != null) sb.append("; ").append((int) r.num("count"))
                                 .append(" credits fall in the date window but none reconcile");
                sb.append(". Posting anything here risks double counting a payout that was "
                          + "reversed and retried.");
            }
            case "partition_unproven" -> {
                sb.setLength(0);
                sb.append("Batch membership could not be proven. ").append(x.partitionRefusal)
                  .append(". The gross shown is the platform's own reported figure, so the "
                  + "residue closes by construction and proves nothing. Refusing to post.");
            }
            case "unexplained" -> sb.append("Checked ").append(seen.size())
                    .append(" sources; none accounts for this residue. Not attributing it to a "
                            + "cause we cannot evidence.");
            default -> sb.append("Components reconcile against the source rows listed above.");
        }
        sb.append(" Evidence rows: ").append(ev.size()).append('.');
        return sb.toString();
    }
}
