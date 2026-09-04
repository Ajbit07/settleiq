package com.settleiq;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;

import com.settleiq.Model.FeeBreakdown;
import com.settleiq.Model.Payment;
import com.settleiq.Model.RateCard;
import com.settleiq.Model.RateTier;

/**
 * The fee model. Reproduces the platform's arithmetic from the PUBLISHED rate
 * card only.
 *
 * It is deliberately NOT told about the mid-month card-rate change that the
 * generator applied. Where the platform actually charged a different rate, this
 * model is wrong by a known, systematic amount, and that error must surface
 * downstream as a `fee_variance` exception rather than being silently absorbed.
 * Handing the model the generator's parameters would make the whole benchmark
 * circular.
 *
 * Rounding is per-payment HALF_UP, then summed -- not summed then rounded.
 * Getting that order wrong produces paise-level drift on every batch.
 */
public final class FeeModel {
    private final RateCard card;
    private final boolean tdsEnabled;

    public FeeModel(RateCard card) { this(card, true); }

    public FeeModel(RateCard card, boolean tdsEnabled) {
        this.card = card;
        this.tdsEnabled = tdsEnabled;
    }

    public RateCard card() { return card; }

    /**
     * Fee, GST and TDS for one payment.
     *
     * TDS u/s 194-O applies only to eligible volume, and eligibility is NOT
     * observable per payment -- the engine cannot see the flag the generator
     * used. We therefore model TDS at the BATCH level from the settlement
     * report's own tds column, and return 0 here. See predictBatch().
     */
    public FeeBreakdown forPayment(Payment p, boolean instantBatch) {
        LocalDate on = p.captureDate();
        RateTier t = card.tierFor(p.method(), on);
        long fee = Money.pctOf(p.amount(), t.pct()) + t.flatPaise();
        if (instantBatch) fee += Money.pctOf(p.amount(), card.instantSurchargePct());
        long gst = Money.pctOf(fee, card.gstPct());
        long reserveBase = card.reserveMethods().contains(p.method())
                ? p.amount() - fee - gst : 0L;
        return new FeeBreakdown(fee, gst, 0L, reserveBase);
    }

    /** Aggregate fee + GST + reserve base over a candidate member set. */
    public FeeBreakdown forSet(List<Payment> members, boolean instantBatch) {
        long fee = 0, gst = 0, rb = 0;
        for (Payment p : members) {
            FeeBreakdown f = forPayment(p, instantBatch);
            fee += f.fee();
            gst += f.gst();
            rb += f.reserveBase();
        }
        return new FeeBreakdown(fee, gst, 0L, rb);
    }

    /** Gross implied by a member set. */
    public static long gross(List<Payment> members) {
        long g = 0;
        for (Payment p : members) g += p.amount();
        return g;
    }

    /**
     * Predicted net credit for a member set, given the batch-level deductions
     * the engine can observe or derive.
     *
     * Every argument is a long of paise. No term is estimated by a model.
     */
    public long predictNet(List<Payment> members, boolean instant,
                           long tds, long refundNetted, long chargeback,
                           long disputeFee, long chargebackReversal,
                           long reserveHeld, long reserveReleased) {
        long g = gross(members);
        FeeBreakdown f = forSet(members, instant);
        long disputeGst = Money.pctOf(disputeFee, card.gstPct());
        return g - f.fee() - f.gst() - (tdsEnabled ? tds : 0L)
                - refundNetted - chargeback - disputeFee - disputeGst
                + chargebackReversal - reserveHeld + reserveReleased;
    }

    /** Rolling reserve withheld on a member set, HALF_UP. */
    public long reserveHeld(List<Payment> members, boolean instant) {
        if (instant) return 0L;
        FeeBreakdown f = forSet(members, instant);
        return Money.pctOf(f.reserveBase(), card.reservePct());
    }

    public long disputeGst(long disputeFee) { return Money.pctOf(disputeFee, card.gstPct()); }

    /** Load the published rate card from the generator's config directory. */
    @SuppressWarnings("unchecked")
    public static RateCard loadCard(java.nio.file.Path p) {
        var m = (java.util.Map<String, Object>) Csv.jsonFile(p);
        var methods = new java.util.LinkedHashMap<String, List<RateTier>>();
        var mm = (java.util.Map<String, Object>) m.get("methods");
        for (var e : mm.entrySet()) {
            var tiers = new java.util.ArrayList<RateTier>();
            for (Object o : (List<Object>) e.getValue()) {
                var t = (java.util.Map<String, Object>) o;
                tiers.add(new RateTier(LocalDate.parse((String) t.get("effective_from")),
                        new BigDecimal((String) t.get("pct")),
                        (long) (double) (Double) t.get("flat_paise")));
            }
            methods.put(e.getKey(), tiers);
        }
        var reserveMethods = new java.util.ArrayList<String>();
        for (Object o : (List<Object>) m.get("reserve_methods")) reserveMethods.add((String) o);
        return new RateCard(
                (String) m.get("merchant_id"), (String) m.get("name"),
                new BigDecimal((String) m.get("gst_rate_pct")),
                new BigDecimal((String) m.get("tds_rate_pct")),
                new BigDecimal((String) m.get("reserve_rate_pct")),
                reserveMethods,
                (int) (double) (Double) m.get("reserve_hold_days"),
                new BigDecimal((String) m.get("instant_surcharge_pct")),
                Money.parse((String) m.get("dispute_fee")),
                (long) (double) (Double) m.get("tolerance_paise"),
                methods);
    }
}
