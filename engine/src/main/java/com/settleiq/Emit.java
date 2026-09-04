package com.settleiq;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

import com.settleiq.Model.Decomposition;
import com.settleiq.Model.Inputs;

/** Writes engine output for the evaluator and the UI. Never reads ground truth. */
public final class Emit {
    private Emit() {}

    /**
     * Candidate pairs with their feature vectors.
     *
     * The trainer consumes THIS file rather than recomputing features in
     * Python. Feature parity between training and serving is then structural,
     * not a convention someone has to remember to maintain.
     */
    public static void dumpCandidates(Path path, Pipeline.Output out) {
        List<String> header = new ArrayList<>(List.of("bank_txn_id", "settlement_id", "source"));
        for (String n : Features.NAMES) header.add("f_" + n);
        List<List<String>> rows = new ArrayList<>();
        for (var c : out.matchResult.allCandidates()) {
            List<String> r = new ArrayList<>(List.of(c.bankTxnId, c.settlementId, c.source));
            for (double v : c.features) r.add(String.format("%.8f", v));
            rows.add(r);
        }
        Csv.write(path, header, rows);
    }

    public static void writeAll(Path outDir, String preset, Inputs in,
                                Pipeline.Output out, long wallMs) {
        Path base = outDir.resolve(preset);

        List<List<String>> links = new ArrayList<>();
        for (var e : out.paymentToSettlement.entrySet()) {
            String setl = e.getValue();
            String bank = "";
            for (var b : out.bankToSettlement.entrySet())
                if (b.getValue().equals(setl)) { bank = b.getKey(); break; }
            links.add(List.of(e.getKey(), setl, bank,
                    out.ambiguousPayments.contains(e.getKey()) ? "1" : "0"));
        }
        Csv.write(base.resolve("predicted_links.csv"),
                List.of("payment_id", "settlement_id", "bank_txn_id", "ambiguous"), links);

        // Bank links are emitted on their own, NOT derived from payment links.
        // Deriving them made every pre-netting ablation row report zero bank
        // links, which understated the matching stages rather than measuring
        // them.
        List<List<String>> bl = new ArrayList<>();
        for (var e : out.bankToSettlement.entrySet())
            bl.add(List.of(e.getKey(), e.getValue(),
                    String.valueOf(out.matchResult.confidence().getOrDefault(e.getKey(), 0.0)),
                    out.matchResult.matchSource().getOrDefault(e.getKey(), ""),
                    out.ambiguousBankTxns.contains(e.getKey()) ? "1" : "0"));
        Csv.write(base.resolve("predicted_bank_links.csv"),
                List.of("bank_txn_id", "settlement_id", "confidence", "source", "ambiguous"), bl);

        List<List<String>> dec = new ArrayList<>();
        for (Decomposition d : out.decompositions) {
            for (var c : d.components.entrySet())
                dec.add(List.of(d.bankTxnId == null ? "" : d.bankTxnId, d.settlementId,
                        c.getKey(), Money.fmt(c.getValue()), String.valueOf(d.confidence)));
            dec.add(List.of(d.bankTxnId == null ? "" : d.bankTxnId, d.settlementId,
                    "residue", Money.fmt(d.residue), String.valueOf(d.confidence)));
            dec.add(List.of(d.bankTxnId == null ? "" : d.bankTxnId, d.settlementId,
                    "TOTAL_bank_credit", Money.fmt(d.bankAmount), String.valueOf(d.confidence)));
        }
        Csv.write(base.resolve("predicted_decomposition.csv"),
                List.of("bank_txn_id", "settlement_id", "component", "amount", "confidence"), dec);

        List<List<String>> ex = new ArrayList<>();
        for (var x : out.exceptions) {
            StringBuilder evb = new StringBuilder();
            String resolution = "", steps = "";
            if (x.trace != null) {
                for (var e : x.trace.evidence()) {
                    if (evb.length() > 0) evb.append(" | ");
                    evb.append(e.render());
                }
                resolution = x.trace.resolution();
                steps = String.join(">", x.trace.steps());
            }
            ex.add(List.of(x.settlementId, x.bankTxnId == null ? "" : x.bankTxnId,
                    x.type == null ? "" : x.type, Money.fmt(x.residue), Money.fmt(x.batchValue),
                    String.valueOf(x.confidence), x.ambiguous ? "1" : "0",
                    x.valueDate == null ? "" : x.valueDate.toString(),
                    x.verdict, x.policyReason, x.hypothesis, x.idempotencyKey,
                    resolution, steps, evb.toString()));
        }
        Csv.write(base.resolve("exceptions.csv"),
                List.of("settlement_id", "bank_txn_id", "type", "residue", "batch_value",
                        "confidence", "ambiguous", "value_date", "verdict", "policy_reason",
                        "hypothesis", "idempotency_key", "resolution", "agent_steps",
                        "evidence"), ex);

        StringBuilder sb = new StringBuilder();
        sb.append("{\"preset\":").append(Csv.q(preset))
          .append(",\"model_version\":").append(Csv.q(out.modelVersion))
          .append(",\"wall_ms\":").append(wallMs)
          .append(",\"payments\":").append(in.payments.size())
          .append(",\"settlements\":").append(in.settlements.size())
          .append(",\"bank_rows\":").append(in.bank.size())
          .append(",\"bank_matched\":").append(out.bankToSettlement.size())
          .append(",\"exact_matches\":").append(out.matchResult.exactCount())
          .append(",\"candidate_pairs\":").append(out.candidatePairs)
          .append(",\"payment_links\":").append(out.paymentToSettlement.size())
          .append(",\"ambiguous_payments\":").append(out.ambiguousPayments.size())
          .append(",\"exceptions\":").append(out.exceptions.size())
          .append(",\"residue_net_paise\":").append(out.totalResidue)
          .append(",\"residue_abs_paise\":").append(out.absResidue)
          .append(",\"gross_matched_paise\":").append(out.grossMatched)
          .append(",\"auto_posted\":").append(out.autoPosted)
          .append(",\"escalated\":").append(out.escalated)
          .append(",\"suppressed_duplicate\":").append(out.suppressed)
          .append(",\"audit_rows\":").append(out.auditRows)
          .append(",\"audit_head\":").append(Csv.q(out.auditHead));
        if (out.netting != null)
            sb.append(",\"h1_dates\":").append(out.netting.h1Dates())
              .append(",\"h2_batches\":").append(out.netting.h2Batches())
              .append(",\"failed_dates\":").append(out.netting.failedDates())
              .append(",\"unassigned_payments\":").append(out.netting.unassignedPaymentIds().size());
        sb.append(",\"timings\":{");
        for (int i = 0; i < out.timings.size(); i++) {
            if (i > 0) sb.append(',');
            sb.append(Csv.q(out.timings.get(i).stage())).append(':').append(out.timings.get(i).millis());
        }
        sb.append("}}");
        Csv.write(base.resolve("summary.json"), List.of(sb.toString()), List.of());
    }
}
