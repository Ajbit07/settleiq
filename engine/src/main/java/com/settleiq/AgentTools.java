package com.settleiq;

import java.time.LocalDate;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import com.settleiq.Model.*;

/**
 * The read-only tool surface the exception agent investigates through.
 *
 * Every tool reads real rows from the loaded inputs and returns facts carrying
 * the id of the row that produced them. Nothing here is a canned response: if a
 * tool has nothing to report it returns an empty result, and the agent has to
 * proceed on that, which is how a genuinely unexplainable residue stays
 * unexplained instead of being attributed to the nearest plausible document.
 *
 * The registry is deliberately READ-ONLY. There is no tool that posts, writes,
 * or mutates anything, so no investigation path -- including one an LLM
 * proposes -- can reach the ledger. Financial writes live in Policy and Audit,
 * which the agent cannot call.
 */
public final class AgentTools {

    /** One fact, with the row it came from. A fact without provenance is dropped. */
    public record Fact(String claim, String provenanceType, String provenanceId, long amountPaise) {}

    /**
     * The outcome of one tool call.
     *
     * `signal` is the machine-readable part the planner branches on; `facts`
     * are the human/auditor-readable part. Keeping them separate is what stops
     * the agent from branching on prose it generated itself.
     */
    public record Result(String tool, String argument, List<Fact> facts,
                         Map<String, Object> signal, long latencyNanos) {
        public boolean empty() { return facts.isEmpty(); }
        public Object sig(String k) { return signal.get(k); }
        public double num(String k) {
            Object v = signal.get(k);
            return v instanceof Number n ? n.doubleValue() : 0;
        }
        public boolean flag(String k) { return Boolean.TRUE.equals(signal.get(k)); }
    }

    /** Tool names the planner may choose from. An unknown name is refused. */
    public static final List<String> TOOLS = List.of(
            "get_settlement",
            "get_bank_candidates",
            "get_payments_in_batch",
            "get_refunds",
            "get_chargebacks",
            "get_reserve_movements",
            "inspect_narration",
            "check_rate_card",
            "check_swap_invariance",
            "check_duplicate_credit",
            "compare_records");

    private final Config cfg;
    private final Inputs in;
    private final FeeModel fees;
    private final Map<String, Settlement> setlById = new LinkedHashMap<>();
    private final Map<String, Payment> payById = new LinkedHashMap<>();
    private final Map<String, BankTxn> bankById = new LinkedHashMap<>();

    public AgentTools(Config cfg, Inputs in, FeeModel fees) {
        this.cfg = cfg;
        this.in = in;
        this.fees = fees;
        for (Settlement s : in.settlements) setlById.put(s.settlementId(), s);
        for (Payment p : in.payments) payById.put(p.paymentId(), p);
        for (BankTxn b : in.bank) bankById.put(b.bankTxnId(), b);
    }

    /** Dispatch. An unknown tool is an error, never a silent empty result. */
    public Result call(String tool, Pipeline.Exception2 x) {
        long t0 = System.nanoTime();
        Result r = switch (tool) {
            case "get_settlement"        -> getSettlement(x);
            case "get_bank_candidates"   -> getBankCandidates(x);
            case "get_payments_in_batch" -> getPaymentsInBatch(x);
            case "get_refunds"           -> getRefunds(x);
            case "get_chargebacks"       -> getChargebacks(x);
            case "get_reserve_movements" -> getReserveMovements(x);
            case "inspect_narration"     -> inspectNarration(x);
            case "check_rate_card"       -> checkRateCard(x);
            case "check_swap_invariance" -> checkSwapInvariance(x);
            case "check_duplicate_credit"-> checkDuplicateCredit(x);
            case "compare_records"       -> compareRecords(x);
            default -> throw new IllegalArgumentException("unknown tool: " + tool);
        };
        return new Result(r.tool(), r.argument(), r.facts(), r.signal(), System.nanoTime() - t0);
    }

    // ───────────────────────────────────────────────────────────────── tools

    private Result getSettlement(Pipeline.Exception2 x) {
        Settlement s = setlById.get(x.settlementId);
        List<Fact> f = new ArrayList<>();
        Map<String, Object> sig = new LinkedHashMap<>();
        if (s == null) {
            sig.put("found", false);
            return res("get_settlement", x.settlementId, f, sig);
        }
        f.add(new Fact("settlement reports net " + Money.fmtInr(s.net()) + " on " + s.settleDate(),
                "settlement", s.settlementId(), s.net()));
        f.add(new Fact("settlement gross " + Money.fmtInr(s.gross()) + ", fees "
                + Money.fmtInr(s.fees()) + ", gst " + Money.fmtInr(s.gst()),
                "settlement", s.settlementId(), s.gross()));
        sig.put("found", true);
        sig.put("net", s.net());
        sig.put("gross", s.gross());
        sig.put("instant", s.instant());
        sig.put("settle_date", String.valueOf(s.settleDate()));
        sig.put("utr_present", s.utr() != null && !s.utr().isBlank());
        return res("get_settlement", x.settlementId, f, sig);
    }

    /**
     * Bank rows that could plausibly be this settlement's credit.
     *
     * "Plausibly" is defined by the same banking-day window the matcher uses,
     * so the agent sees the candidate set the matcher actually faced rather
     * than a prettier one.
     */
    private Result getBankCandidates(Pipeline.Exception2 x) {
        Settlement s = setlById.get(x.settlementId);
        List<Fact> f = new ArrayList<>();
        Map<String, Object> sig = new LinkedHashMap<>();
        if (s == null) { sig.put("count", 0); return res("get_bank_candidates", x.settlementId, f, sig); }

        int within = 0;
        long bestDelta = Long.MAX_VALUE;
        String bestId = null;
        for (BankTxn b : in.bank) {
            if (b.amount() <= 0) continue;
            int bd = Math.abs(BankingCalendar.between(s.settleDate(), b.valueDate()));
            if (bd > cfg.dateWindowBankingDays) continue;
            within++;
            long d = Math.abs(b.amount() - s.net());
            if (d < bestDelta) { bestDelta = d; bestId = b.bankTxnId(); }
        }
        if (bestId != null) {
            BankTxn b = bankById.get(bestId);
            f.add(new Fact("closest bank credit in the window is " + Money.fmtInr(b.amount())
                    + " on " + b.valueDate() + ", delta " + Money.fmtInr(b.amount() - s.net()),
                    "bank_txn", bestId, b.amount()));
        }
        f.add(new Fact(within + " bank credits fall inside the ±"
                + cfg.dateWindowBankingDays + " banking-day window",
                "settlement", s.settlementId(), 0));
        sig.put("count", within);
        sig.put("best_delta", bestDelta == Long.MAX_VALUE ? -1 : bestDelta);
        sig.put("best_bank_txn", bestId);
        sig.put("linked", x.bankTxnId != null);
        return res("get_bank_candidates", x.settlementId, f, sig);
    }

    private Result getPaymentsInBatch(Pipeline.Exception2 x) {
        List<Fact> f = new ArrayList<>();
        Map<String, Object> sig = new LinkedHashMap<>();
        long gross = 0, cardVol = 0;
        Map<String, Integer> byMethod = new LinkedHashMap<>();
        String sampleCard = null;
        for (String pid : x.memberPaymentIds) {
            Payment p = payById.get(pid);
            if (p == null) continue;
            gross += p.amount();
            byMethod.merge(p.method(), 1, Integer::sum);
            if ("card".equals(p.method())) {
                cardVol += p.amount();
                if (sampleCard == null) sampleCard = pid;
            }
        }
        if (!x.memberPaymentIds.isEmpty())
            f.add(new Fact("batch reconstructed from " + x.memberPaymentIds.size()
                    + " payments, gross " + Money.fmtInr(gross),
                    "settlement", x.settlementId, gross));
        if (sampleCard != null)
            f.add(new Fact("card volume in this batch " + Money.fmtInr(cardVol),
                    "payment", sampleCard, cardVol));
        sig.put("members", x.memberPaymentIds.size());
        sig.put("gross", gross);
        sig.put("card_volume", cardVol);
        sig.put("methods", byMethod);
        sig.put("sample_card_payment", sampleCard);
        return res("get_payments_in_batch", x.settlementId, f, sig);
    }

    private Result getRefunds(Pipeline.Exception2 x) {
        List<Fact> f = new ArrayList<>();
        Map<String, Object> sig = new LinkedHashMap<>();
        long total = 0;
        int n = 0;
        long matchingResidue = 0;
        for (Refund r : in.refunds) {
            if (!r.netted() || !x.memberPaymentIds.contains(r.paymentId())) continue;
            n++;
            total += r.amount();
            f.add(new Fact("refund " + Money.fmtInr(r.amount()) + " netted into this payout",
                    "refund", r.refundId(), r.amount()));
            if (Math.abs(r.amount() - Math.abs(x.residue)) <= cfg.tolerancePaise)
                matchingResidue = r.amount();
        }
        sig.put("count", n);
        sig.put("total", total);
        sig.put("explains_residue", matchingResidue != 0);
        return res("get_refunds", x.settlementId, f, sig);
    }

    private Result getChargebacks(Pipeline.Exception2 x) {
        List<Fact> f = new ArrayList<>();
        Map<String, Object> sig = new LinkedHashMap<>();
        long total = 0;
        int n = 0;
        for (Chargeback c : in.chargebacks) {
            if (!x.memberPaymentIds.contains(c.paymentId())) continue;
            n++;
            total += c.amount() + c.fee();
            f.add(new Fact("chargeback " + Money.fmtInr(c.amount()) + " plus fee "
                    + Money.fmtInr(c.fee()), "chargeback", c.disputeId(), c.amount()));
        }
        sig.put("count", n);
        sig.put("total", total);
        sig.put("explains_residue", n > 0
                && Math.abs(total - Math.abs(x.residue)) <= cfg.tolerancePaise);
        return res("get_chargebacks", x.settlementId, f, sig);
    }

    private Result getReserveMovements(Pipeline.Exception2 x) {
        Settlement s = setlById.get(x.settlementId);
        List<Fact> f = new ArrayList<>();
        Map<String, Object> sig = new LinkedHashMap<>();
        boolean explains = false;
        int n = 0;
        if (s != null) {
            for (ReserveEntry r : in.reserve) {
                if (!r.releaseDate().equals(s.settleDate())) continue;
                n++;
                f.add(new Fact("reserve " + Money.fmtInr(r.amount()) + " held " + r.heldOn()
                        + " released " + r.releaseDate(), "reserve", r.reserveId(), r.amount()));
                if (Math.abs(r.amount() - Math.abs(x.residue)) <= cfg.tolerancePaise) explains = true;
            }
        }
        sig.put("count", n);
        sig.put("explains_residue", explains);
        return res("get_reserve_movements", x.settlementId, f, sig);
    }

    private Result inspectNarration(Pipeline.Exception2 x) {
        List<Fact> f = new ArrayList<>();
        Map<String, Object> sig = new LinkedHashMap<>();
        BankTxn b = x.bankTxnId == null ? null : bankById.get(x.bankTxnId);
        Settlement s = setlById.get(x.settlementId);
        if (b == null) {
            sig.put("has_bank_row", false);
            return res("inspect_narration", String.valueOf(x.bankTxnId), f, sig);
        }
        Normalizer.Parsed parsed = Normalizer.parse(b.bankTxnId(), b.narration());
        boolean exact = s != null && !s.utr().isBlank() && parsed.utrCandidates().contains(s.utr());
        f.add(new Fact("narration reads \"" + b.narration() + "\"", "bank_txn", b.bankTxnId(), 0));
        if (!parsed.utrCandidates().isEmpty())
            f.add(new Fact("narration carries reference token " + parsed.utrCandidates().get(0)
                    + (exact ? " which matches the settlement UTR exactly"
                             : " which does not match the settlement UTR"),
                    "bank_txn", b.bankTxnId(), 0));
        sig.put("has_bank_row", true);
        sig.put("utr_exact", exact);
        sig.put("candidate_count", parsed.utrCandidates().size());
        sig.put("similarity", s == null ? 0.0
                : Features.jaroWinkler(Features.bestToken(parsed, s.utr()), s.utr()));
        return res("inspect_narration", b.bankTxnId(), f, sig);
    }

    /**
     * Prices the batch off the PUBLISHED rate card and reports how far the
     * observed residue is from that price, as an implied rate on card volume.
     *
     * This is the arithmetic that makes "fee_variance" a claim rather than a
     * guess: the number comes from the rate card and the member payments, and
     * the agent may only report it, never assert one of its own.
     */
    private Result checkRateCard(Pipeline.Exception2 x) {
        List<Fact> f = new ArrayList<>();
        Map<String, Object> sig = new LinkedHashMap<>();
        long cardVol = 0;
        String sample = null;
        for (String pid : x.memberPaymentIds) {
            Payment p = payById.get(pid);
            if (p == null || !"card".equals(p.method())) continue;
            cardVol += p.amount();
            if (sample == null) sample = pid;
        }
        sig.put("card_volume", cardVol);
        if (sample == null || cardVol == 0) {
            sig.put("implied_excess_pct", 0.0);
            sig.put("consistent_with_fee_variance", false);
            return res("check_rate_card", x.settlementId, f, sig);
        }
        var tier = fees.card().tierFor("card", payById.get(sample).captureDate());
        double impliedPct = Math.abs(x.residue) * 100.0 / cardVol;
        f.add(new Fact("published card rate in force is " + tier.pct() + "% from "
                + tier.effectiveFrom(), "rate_card", fees.card().merchantId(), 0));
        f.add(new Fact(String.format(
                "residue implies an effective rate %.4f%% above the published card rate", impliedPct),
                "settlement", x.settlementId, x.residue));
        // Direction matters: pricing from the published card, a HIGHER charge
        // arrives as a smaller credit, so a fee variance is a negative residue.
        boolean consistent = x.residue < 0 && impliedPct > 0.02 && impliedPct < 1.50;
        sig.put("implied_excess_pct", impliedPct);
        sig.put("published_pct", tier.pct().doubleValue());
        sig.put("consistent_with_fee_variance", consistent);
        return res("check_rate_card", x.settlementId, f, sig);
    }

    private Result checkSwapInvariance(Pipeline.Exception2 x) {
        List<Fact> f = new ArrayList<>();
        Map<String, Object> sig = new LinkedHashMap<>();
        sig.put("ambiguous", x.ambiguous);
        if (x.ambiguous)
            f.add(new Fact("two or more payments in this batch are swap-invariant: same amount, "
                    + "method and rate tier as a payment in another batch settling the same day",
                    "settlement", x.settlementId, 0));
        return res("check_swap_invariance", x.settlementId, f, sig);
    }

    private Result checkDuplicateCredit(Pipeline.Exception2 x) {
        List<Fact> f = new ArrayList<>();
        Map<String, Object> sig = new LinkedHashMap<>();
        sig.put("unmatched", x.unmatched);
        if (x.unmatched)
            f.add(new Fact("no bank credit could be linked to this settlement; a reversed payout "
                    + "or an unmatched retry is the likely cause", "settlement", x.settlementId, 0));
        return res("check_duplicate_credit", x.settlementId, f, sig);
    }

    private Result compareRecords(Pipeline.Exception2 x) {
        Settlement s = setlById.get(x.settlementId);
        List<Fact> f = new ArrayList<>();
        Map<String, Object> sig = new LinkedHashMap<>();
        BankTxn b = x.bankTxnId == null ? null : bankById.get(x.bankTxnId);
        if (s == null || b == null) {
            sig.put("comparable", false);
            return res("compare_records", x.settlementId, f, sig);
        }
        long delta = b.amount() - s.net();
        int bd = BankingCalendar.between(s.settleDate(), b.valueDate());
        f.add(new Fact("bank credited " + Money.fmtInr(b.amount()) + " value date " + b.valueDate()
                + " against settlement net " + Money.fmtInr(s.net()) + " on " + s.settleDate()
                + ": delta " + Money.fmtInr(delta) + ", " + bd + " banking days",
                "bank_txn", b.bankTxnId(), delta));
        sig.put("comparable", true);
        sig.put("delta", delta);
        sig.put("banking_day_delta", bd);
        sig.put("within_tolerance", Math.abs(delta) <= cfg.tolerancePaise);
        return res("compare_records", x.settlementId, f, sig);
    }

    private static Result res(String tool, String arg, List<Fact> f, Map<String, Object> sig) {
        return new Result(tool, arg, f, sig, 0);
    }
}
