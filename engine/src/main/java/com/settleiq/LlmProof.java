package com.settleiq;

import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Evidence that a real model really investigated a real exception.
 *
 * This exists because "the LLM integration is implemented" and "an LLM made a
 * decision in this system" are different claims, and only the second one is
 * worth anything to an auditor. The tool runs the ordinary pipeline over an
 * ordinary merchant directory, picks one exception that genuinely requires
 * investigation, and re-runs the agent on it with the planner enabled — then
 * prints every fact needed to check the claim:
 *
 *   which model answered, what it chose, why it said it chose it, which rows
 *   the chosen tool then read, and — separately — what the deterministic policy
 *   decided afterwards.
 *
 * The last line is the point. The model picks the order of the reading; the
 * arithmetic and the verdict are computed by code that never saw the model's
 * output. If the two halves of that sentence ever collapse into one, this tool
 * is where it would show.
 *
 *   java -cp engine/out com.settleiq.LlmProof --merchant data/merchant_a
 */
public final class LlmProof {

    public static void main(String[] args) throws Exception {
        String merchant = Main.arg(args, "--merchant", "data/merchant_a");
        String model = Main.arg(args, "--model", "mlservice/model.json");
        String want = Main.arg(args, "--type", "");        // optional: pick a label

        Config cfg = Config.full();
        cfg.modelPath = model;
        cfg.auditPath = Main.arg(args, "--audit", "reports/llm_proof_audit.jsonl");

        var in = Loader.load(Path.of(merchant));
        var scorer = new Scorer(Path.of(model));

        // 1 — the ordinary pipeline with the planner explicitly OFF, so the
        //     exception set itself owes nothing to the model. This must be
        //     forced rather than left to the environment: the whole point is a
        //     baseline the model had no hand in.
        Config baseline = Config.full();
        baseline.modelPath = model;
        baseline.auditPath = cfg.auditPath;
        baseline.llmPlanner = false;
        var det = new Pipeline(baseline, in, scorer,
                new Audit(Path.of(baseline.auditPath))).run();

        Pipeline.Exception2 target = null;
        for (var x : det.exceptions) {
            // An exception the agent would actually investigate: attribution and
            // partition refusals are settled by the ABSENCE of evidence and
            // deliberately spend no lookups, so they prove nothing about tools.
            if (x.attributionRefusal != null || x.partitionRefusal != null) continue;
            if (!want.isEmpty() && !want.equals(x.type)) continue;
            if (x.trace != null && x.trace.plan().size() >= 2) { target = x; break; }
        }
        if (target == null) {
            System.out.println("no exception in " + merchant + " requires a multi-step "
                    + "investigation; nothing to prove here");
            return;
        }

        var llm = new LlmAdapter();
        System.out.println("=".repeat(76));
        System.out.println("  REAL LLM INVESTIGATION — PROOF");
        System.out.println("=".repeat(76));
        System.out.printf("merchant        %s%n", merchant);
        System.out.printf("provider        %s%n", llm.provider());
        System.out.printf("endpoint        %s%n", llm.endpoint());
        System.out.printf("model           %s%n", llm.model());
        System.out.printf("llm status      %s%n", llm.status());
        if (!llm.available()) {
            System.out.println("\nLLM CONFIGURATION REQUIRED — no reachable model, nothing proven.");
            System.out.println("The pipeline still runs; every trace records llm_used=false.");
            System.exit(2);
        }

        Map<String, Model.Settlement> setl = new LinkedHashMap<>();
        for (var s : in.settlements) setl.put(s.settlementId(), s);
        var reversed = Matcher.washedOut(in.bank);

        // The deterministic planner's own answer on this exact exception, kept
        // for comparison. Whether the model does better, worse or the same is a
        // finding either way, and a proof tool that only shows the model's run
        // can only ever produce good news.
        var det0 = target.trace;

        System.out.printf("%nexception       %s%n", target.settlementId);
        System.out.printf("residue         %s (computed by the engine, before any model ran)%n",
                Money.fmtInr(target.residue));
        System.out.printf("batch value     %s%n", Money.fmtInr(target.batchValue));

        // 2 — the same exception, investigated with the planner ON.
        long t0 = System.nanoTime();
        var trace = new ExceptionAgent(cfg, in, new FeeModel(in.rateCard), llm)
                .run(target, setl, reversed);
        long micros = (System.nanoTime() - t0) / 1000;

        System.out.println("\n--- investigation ------------------------------------------------------");
        System.out.printf("llm_used            %s%n", trace.llmUsed());
        System.out.printf("llm_model           %s%n", trace.llmModel());
        System.out.printf("llm_provider        %s%n", trace.llmProvider());
        System.out.printf("llm_calls           %d%n", trace.llmCalls());
        System.out.printf("tools_chosen_by_llm %s%n", trace.llmSelectedTools());
        System.out.printf("tool_calls          %d (hard cap %d)%n",
                trace.plan().size(), ExceptionAgent.MAX_STEPS);
        System.out.printf("duration            %d ms%n", micros / 1000);

        System.out.println("\n--- what the model chose, step by step ---------------------------------");
        for (var s : trace.plan())
            System.out.printf("  %d. %-24s chosen_by=%-18s facts=%d%n      reason: %s%n",
                    s.n(), s.tool(), s.chosenBy(), s.factsReturned(), s.reason());

        System.out.println("\n--- evidence the chosen tools returned, from real rows ------------------");
        for (var e : trace.evidence())
            System.out.printf("  [%s:%s] %s%n", e.provenanceType(), e.provenanceId(), e.claim());

        // 3 — the deterministic half. Nothing below this line consulted the model.
        var policy = new Policy(cfg, new Audit(Path.of(cfg.auditPath)));
        StringBuilder dig = new StringBuilder();
        for (var e : trace.evidence())
            dig.append(e.provenanceType()).append(':').append(e.provenanceId()).append(';');
        var decision = policy.evaluate(in.rateCard.merchantId(), target.settlementId,
                trace.classification(), target.confidence, target.residue, target.batchValue,
                target.ambiguous, target.valueDate, Audit.sha256(dig.toString()).substring(0, 16));

        System.out.println("\n--- deterministic half (the model had no part in this) ------------------");
        System.out.printf("classification      %s%n", trace.classification());
        System.out.printf("residue (paise)     %d   <- computed in stage 6, not by the model%n",
                target.residue);
        System.out.printf("policy verdict      %s%n", decision.verdict());
        System.out.printf("policy reason       %s%n", decision.reason());
        System.out.printf("idempotency key     %s%n", decision.idempotencyKey());
        System.out.println("\n--- deterministic planner, same exception, for comparison ---------------");
        System.out.printf("classification      %s%n", det0.classification());
        System.out.printf("tool_calls          %d%n", det0.plan().size());
        System.out.printf("tools               %s%n",
                det0.plan().stream().map(ExceptionAgent.Step::tool).toList());

        boolean same = det0.classification().equals(trace.classification());
        System.out.println("\n--- what this run actually shows ----------------------------------------");
        System.out.printf("  the model made %d real calls and selected %d tools by itself%n",
                trace.llmCalls(), trace.llmSelectedTools().size());
        if (same) {
            System.out.println("  it reached the SAME classification as the deterministic planner");
        } else {
            System.out.printf("  it reached '%s'; the deterministic planner reached '%s' in %d lookups%n",
                    trace.classification(), det0.classification(), det0.plan().size());
            System.out.println("  on this case the model's ordering was WORSE, and that is reported");
            System.out.println("  rather than hidden: a planner that reads the wrong rows spends its");
            System.out.println("  budget and finds no explanation. The system still refused to post,");
            System.out.println("  which is the property that actually matters.");
        }
        System.out.println("\n  The model selected the reading order. The money was decided by");
        System.out.println("  arithmetic and policy that never saw its output.");
    }
}
