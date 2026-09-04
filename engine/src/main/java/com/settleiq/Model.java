package com.settleiq;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/** Domain records. All amounts are long paise. */
public final class Model {
    private Model() {}

    public record Payment(String paymentId, String orderId, long amount, String method,
                          String status, LocalDateTime createdAtIst) {
        public LocalDate captureDate() { return createdAtIst.toLocalDate(); }
        public boolean captured() { return "captured".equals(status); }
    }

    public record Order(String orderId, String customerId, long amount, LocalDateTime createdAtUtc) {}

    public record Refund(String refundId, String paymentId, long amount,
                         LocalDateTime createdAtIst, boolean netted) {}

    public record Chargeback(String disputeId, String paymentId, long amount, long fee,
                             LocalDateTime raisedAtIst, LocalDateTime reversedAtIst) {
        public boolean reversed() { return reversedAtIst != null; }
    }

    /** Rolling-reserve statement line. Observable: merchants receive this. */
    public record ReserveEntry(String reserveId, long amount, LocalDate heldOn,
                               LocalDate releaseDate, LocalDateTime releasedAtIst,
                               boolean netted) {}

    public record Settlement(String settlementId, String utr, long gross, long fees, long gst,
                             long tds, long net, LocalDateTime settledAt, boolean instant) {
        public LocalDate settleDate() { return settledAt.toLocalDate(); }
    }

    public record BankTxn(String bankTxnId, LocalDate valueDate, long amount, String narration) {
        public boolean credit() { return amount > 0; }
    }

    /** One effective-dated tier of the published rate card. */
    public record RateTier(LocalDate effectiveFrom, java.math.BigDecimal pct, long flatPaise) {}

    public record RateCard(String merchantId, String name,
                           java.math.BigDecimal gstPct, java.math.BigDecimal tdsPct,
                           java.math.BigDecimal reservePct, List<String> reserveMethods,
                           int reserveHoldDays, java.math.BigDecimal instantSurchargePct,
                           long disputeFee, long tolerancePaise,
                           Map<String, List<RateTier>> methods) {
        /** Tier in force for a method on a date. The PUBLISHED card has exactly
         *  one tier per method; a mid-month change is invisible here by design. */
        public RateTier tierFor(String method, LocalDate on) {
            List<RateTier> tiers = methods.get(method);
            if (tiers == null || tiers.isEmpty())
                throw new IllegalArgumentException("no rate card tier for method " + method);
            RateTier chosen = tiers.get(0);
            for (RateTier t : tiers) if (!on.isBefore(t.effectiveFrom())) chosen = t;
            return chosen;
        }
    }

    /** Everything the engine is allowed to read. */
    public static final class Inputs {
        public List<Payment> payments = new ArrayList<>();
        public List<Order> orders = new ArrayList<>();
        public List<Refund> refunds = new ArrayList<>();
        public List<Chargeback> chargebacks = new ArrayList<>();
        public List<Settlement> settlements = new ArrayList<>();
        public List<ReserveEntry> reserve = new ArrayList<>();
        public List<BankTxn> bank = new ArrayList<>();
        public RateCard rateCard;
    }

    /** Per-payment fee breakdown predicted by the engine's fee model. */
    public record FeeBreakdown(long fee, long gst, long tds, long reserveBase) {}

    /** A candidate (bankTxn, settlement) pairing with its features and score. */
    public static final class Candidate {
        public final String bankTxnId;
        public final String settlementId;
        public double[] features;
        public double rawScore;
        public double probability;
        public String source;          // exact_utr | repaired_utr | amount_date | blocked
        public Candidate(String bankTxnId, String settlementId, String source) {
            this.bankTxnId = bankTxnId; this.settlementId = settlementId; this.source = source;
        }
    }

    /** Final decomposition of one bank credit. */
    public static final class Decomposition {
        public String bankTxnId;
        public String settlementId;         // null when unmatched
        public long bankAmount;
        public java.util.LinkedHashMap<String, Long> components = new java.util.LinkedHashMap<>();
        public long residue;
        public boolean withinTolerance;
        public boolean ambiguous;
        public boolean unmatched;
        /** False when the partition solver ran and could not prove membership.
         *  Such a batch may still show a small residue, because its gross came
         *  from the platform's own report rather than from reconstructed rows —
         *  it is NOT reconciled, and must never be treated as such. */
        public boolean membershipProven = true;
        public String note = "";
        public List<String> memberPaymentIds = new ArrayList<>();
        public double confidence;
    }
}
