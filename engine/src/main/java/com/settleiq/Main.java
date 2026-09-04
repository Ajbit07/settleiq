package com.settleiq;

import java.nio.file.Path;
import java.util.List;

/** CLI entry point: run the pipeline over one merchant directory. */
public final class Main {
    public static void main(String[] args) throws Exception {
        String merchant = arg(args, "--merchant", "data/merchant_a");
        String outDir = arg(args, "--out", "reports");
        String preset = arg(args, "--preset", "full");
        String model = arg(args, "--model", "mlservice/model.json");

        Config cfg = switch (preset) {
            case "deterministic" -> Config.deterministicOnly();
            case "repair" -> Config.plusRepair();
            case "scorer" -> Config.plusScorer();
            case "global" -> Config.plusGlobalAssignment();
            case "netting" -> Config.plusNetting();
            case "stress_greedy" -> Config.stressGreedy();
            case "stress_global" -> Config.stressGlobal();
            default -> Config.full();
        };
        cfg.modelPath = model;
        cfg.auditPath = arg(args, "--audit", "reports/audit_ledger.jsonl");

        long t0 = System.currentTimeMillis();
        var in = Loader.load(Path.of(merchant));
        Scorer scorer = new Scorer(cfg.stageScorer ? Path.of(model) : null);
        var out = new Pipeline(cfg, in, scorer).run();
        long wall = System.currentTimeMillis() - t0;

        System.out.printf("preset=%s  %s%n", preset, cfg.describe());
        System.out.printf("model=%s (loaded=%s)%n", scorer.modelVersion, scorer.isLoaded());
        System.out.printf("payments=%d settlements=%d bank_rows=%d%n",
                in.payments.size(), in.settlements.size(), in.bank.size());
        System.out.printf("bank<->settlement matched=%d (exact=%d) candidate_pairs=%d%n",
                out.bankToSettlement.size(), out.matchResult.exactCount(), out.candidatePairs);
        if (out.netting != null)
            System.out.printf("netting: H1_dates=%d H2_batches=%d failed_dates=%d unassigned_payments=%d%n",
                    out.netting.h1Dates(), out.netting.h2Batches(), out.netting.failedDates(),
                    out.netting.unassignedPaymentIds().size());
        if (out.netting != null) {
            for (String dline : out.netting.failureDiagnostics())
                System.out.println("  netting-fail: " + dline);
            if (!out.netting.solverOutcomes().isEmpty())
                System.out.printf("netting-solver: %s%n", out.netting.solverOutcomes());
            for (var r : out.netting.refusals())
                System.out.printf("  netting-refused: %s %s target=%s pool=%d states=%d %dus — %s%n",
                        r.settlementId(), r.outcome(), Money.fmtInr(r.targetPaise()),
                        r.poolSize(), r.statesExplored(), r.micros(), r.reason());
        }
        if (!out.unattributed.isEmpty()) {
            java.util.Map<String, Integer> byBasis = new java.util.LinkedHashMap<>();
            long refusedValue = 0;
            for (var u : out.unattributed) {
                byBasis.merge(u.basis, 1, Integer::sum);
                refusedValue += u.amountPaise;
            }
            System.out.printf("attribution: refused=%d value=%s basis=%s%n",
                    out.unattributed.size(), Money.fmtInr(refusedValue), byBasis);
        }
        System.out.printf("payment->settlement links=%d ambiguous_payments=%d%n",
                out.paymentToSettlement.size(), out.ambiguousPayments.size());
        System.out.printf("residue: net=%s abs=%s  exceptions=%d%n",
                Money.fmtInr(out.totalResidue), Money.fmtInr(out.absResidue), out.exceptions.size());
        System.out.printf("exceptions: auto_posted=%d escalated=%d suppressed_duplicate=%d%n",
                out.autoPosted, out.escalated, out.suppressed);
        if (cfg.stageAgent) {
            var v = Audit.verify(Path.of(cfg.auditPath));
            System.out.printf("audit: rows_appended=%d duplicates_suppressed=%d "
                            + "chain_valid=%s head=%s%n",
                    out.auditRows, out.auditSuppressed, v.valid(),
                    v.head().substring(0, 16) + "...");
        }
        // LLM telemetry, aggregated across every investigation in this run.
        int llmTraces = 0, llmCalls = 0, multiStep = 0;
        String llmModel = null, llmProvider = null, llmStatus = null;
        java.util.Map<String, Integer> llmTools = new java.util.LinkedHashMap<>();
        for (var x : out.exceptions) {
            if (x.trace == null) continue;
            if (llmStatus == null) { llmStatus = x.trace.llmStatus(); llmProvider = x.trace.llmProvider(); }
            if (!x.trace.llmUsed()) continue;
            llmTraces++;
            llmCalls += x.trace.llmCalls();
            llmModel = x.trace.llmModel();
            var picked = x.trace.llmSelectedTools();
            if (picked.size() > 1) multiStep++;
            for (String t : picked) llmTools.merge(t, 1, Integer::sum);
        }
        System.out.printf("llm: provider=%s status=%s%n", llmProvider, llmStatus);
        System.out.printf("llm: investigations_with_llm=%d/%d calls=%d multi_step=%d model=%s%n",
                llmTraces, out.exceptions.size(), llmCalls, multiStep, llmModel);
        if (!llmTools.isEmpty())
            System.out.printf("llm: tools_selected_by_llm=%s%n", llmTools);

        System.out.printf("wall=%dms%n", wall);
        for (var t : out.timings) System.out.printf("  %-20s %5d ms%n", t.stage(), t.millis());

        Emit.writeAll(Path.of(outDir), preset, in, out, wall);

        String serve = arg(args, "--serve", "");
        if (!serve.isEmpty()) {
            new HttpApi(in, out, cfg, scorer, preset, wall,
                    Path.of("api/src/main/resources/static")).start(Integer.parseInt(serve));
            Thread.currentThread().join();
        }
        String dump = arg(args, "--dump-candidates", "");
        if (!dump.isEmpty()) {
            Emit.dumpCandidates(Path.of(dump), out);
            System.out.println("candidates dumped -> " + dump);
        }
    }

    static String arg(String[] a, String k, String d) {
        for (int i = 0; i + 1 < a.length; i++) if (a[i].equals(k)) return a[i + 1];
        return d;
    }
}
