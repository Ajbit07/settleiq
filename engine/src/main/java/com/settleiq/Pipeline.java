package com.settleiq;

import java.nio.file.Path;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

import com.settleiq.Model.*;

/** Stages 0-7 end to end. Every stage is measurable and independently disabled. */
public final class Pipeline {

    public record Timing(String stage, long millis) {}

    public static final class Output {
        public List<Decomposition> decompositions = new ArrayList<>();
        public Map<String, String> paymentToSettlement = new LinkedHashMap<>();
        public Map<String, String> bankToSettlement = new LinkedHashMap<>();
        public Set<String> ambiguousPayments = new HashSet<>();
        public Set<String> ambiguousBankTxns = new HashSet<>();
        public List<Exception2> exceptions = new ArrayList<>();
        public List<Timing> timings = new ArrayList<>();
        public Matcher.Result matchResult;
        public Netting.Assignment netting;
        public long totalResidue, absResidue, grossMatched;
        public int candidatePairs;
        public String modelVersion;
        public int autoPosted, escalated, suppressed, auditSuppressed, auditRows;
        public String auditHead = "";
        /** Adjustments the engine declined to attribute to any batch. */
        public List<UnattributedAdjustment> unattributed = new ArrayList<>();
    }

    /** An unexplained residue handed to the exception agent. */
    public static final class Exception2 {
        public String settlementId, bankTxnId, type;
        public long residue, batchValue;
        public double confidence;
        public boolean ambiguous;
        public boolean unmatched;
        public boolean alreadyPosted;
        public LocalDate valueDate;
        public List<String> evidence = new ArrayList<>();
        public String hypothesis = "", policyReason = "", verdict = "";
        public String idempotencyKey = "";
        public Map<String, Long> componentTrace = new LinkedHashMap<>();
        public List<String> memberPaymentIds = new ArrayList<>();
        public ExceptionAgent.Trace trace;
        /** Set when this exception IS a refused adjustment attribution. */
        public UnattributedAdjustment attributionRefusal;
        /** Set when the partition solver could not prove this batch's members. */
        public String partitionRefusal;
        public Policy.Decision decision;
    }

    private final Config cfg;
    private final Inputs in;
    private final FeeModel fees;
    private final Scorer scorer;
    private final AuditSink sink;

    public Pipeline(Config cfg, Inputs in, Scorer scorer) {
        this(cfg, in, scorer, null);
    }

    /**
     * @param sink where audit entries go. Null means the file-backed ledger at
     *             {@code cfg.auditPath}, which is what the CLI uses. The API
     *             passes a Postgres-backed sink so idempotency is enforced by a
     *             UNIQUE constraint rather than by an in-process set.
     */
    public Pipeline(Config cfg, Inputs in, Scorer scorer, AuditSink sink) {
        this.cfg = cfg;
        this.in = in;
        this.fees = new FeeModel(in.rateCard);
        this.scorer = scorer;
        this.sink = sink;
    }

    public Output run() {
        Output out = new Output();
        out.modelVersion = scorer.modelVersion;
        long t;

        // ---- Stage 0: normalisation ----------------------------------------
        t = System.currentTimeMillis();
        Map<String, Normalizer.Parsed> parsed = new LinkedHashMap<>();
        for (BankTxn b : in.bank)
            parsed.put(b.bankTxnId(), cfg.stageNormalise
                    ? Normalizer.parse(b.bankTxnId(), b.narration())
                    : new Normalizer.Parsed(b.bankTxnId(), b.narration(), b.narration(),
                            null, List.of(), Set.of(), "", List.of()));
        out.timings.add(new Timing("stage0_normalise", System.currentTimeMillis() - t));

        // ---- Stages 1-4: bank credit <-> settlement -------------------------
        t = System.currentTimeMillis();
        Matcher.Result mr = new Matcher(cfg, scorer).match(in.bank, in.settlements, parsed);
        out.matchResult = mr;
        out.bankToSettlement = mr.bankToSettlement();
        out.ambiguousBankTxns = mr.ambiguousBankTxns();
        out.candidatePairs = mr.candidatePairs();
        out.timings.add(new Timing("stage1_4_match", System.currentTimeMillis() - t));

        // ---- Stage 5: netting decomposition ---------------------------------
        t = System.currentTimeMillis();
        Netting.Assignment na = null;
        if (cfg.stageNetting) {
            na = new Netting(fees, cfg.stageNettingDp, cfg.dpMaxItems)
                    .solve(in.payments, in.settlements);
            out.netting = na;
            out.ambiguousPayments = na.ambiguousPaymentIds();
            for (var e : na.members().entrySet())
                for (Payment p : e.getValue())
                    out.paymentToSettlement.put(p.paymentId(), e.getKey());
        }
        out.timings.add(new Timing("stage5_netting", System.currentTimeMillis() - t));

        // ---- Stage 6: decomposition + residue -------------------------------
        t = System.currentTimeMillis();
        buildDecompositions(out, na);
        out.timings.add(new Timing("stage6_decompose", System.currentTimeMillis() - t));

        // ---- Stage 6b: exception agent, Stage 7: policy + audit -------------
        t = System.currentTimeMillis();
        if (cfg.stageAgent) runAgentAndPolicy(out);
        out.timings.add(new Timing("stage6b_agent_policy", System.currentTimeMillis() - t));
        return out;
    }

    /**
     * A refused attribution, as an exception a human can act on.
     *
     * It carries the adjustment, its value, every batch the inferred boundary
     * would have chosen between, and the reason automatic attribution was
     * declined -- which is the whole point: the operator sees the guess that was
     * NOT made, and the evidence that would have been needed to make it.
     */
    private Exception2 toException(UnattributedAdjustment u) {
        Exception2 x = new Exception2();
        x.settlementId = u.candidates.isEmpty() ? "unattributed" : u.candidates.get(0);
        x.bankTxnId = null;
        x.type = "attribution_ambiguous";
        x.residue = u.amountPaise;
        x.batchValue = u.amountPaise;
        x.confidence = 0.0;             // no evidence establishes ownership
        x.ambiguous = true;             // never auto-postable
        x.valueDate = u.settleDate;
        x.attributionRefusal = u;
        return x;
    }

    /**
     * Stage 6b + 7. The agent classifies and explains; Policy decides; the
     * audit ledger records. Nothing the agent produced becomes a number: the
     * residue and batch value passed to Policy were computed in Stage 6.
     */
    private void runAgentAndPolicy(Output out) {
        Map<String, Settlement> setlById = new LinkedHashMap<>();
        for (Settlement s : in.settlements) setlById.put(s.settlementId(), s);
        Set<String> reversed = Matcher.washedOut(in.bank);

        // Every refused attribution becomes a first-class exception before the
        // agent runs, so an adjustment the engine could not place is worked and
        // escalated like any other break instead of quietly widening a residue.
        for (UnattributedAdjustment u : out.unattributed) out.exceptions.add(toException(u));

        ExceptionAgent agent = new ExceptionAgent(cfg, in, fees);
        AuditSink audit = (sink != null) ? sink
                : new Audit(java.nio.file.Path.of(cfg.auditPath));
        Policy policy = new Policy(cfg, audit);
        String merchant = in.rateCard.merchantId();

        /*
         * The planner's call budget is finite, so the order this loop runs in
         * decides which exceptions get a planned investigation and which fall
         * back to the deterministic one. Left in construction order that choice
         * is arbitrary -- whichever settlement happened to be built first.
         *
         * Spend it where a different lookup ORDER could still change the
         * answer: on the largest unexplained residues. A swap-invariant case is
         * deliberately sorted last, because its verdict is already fixed by
         * arithmetic -- no sequence of lookups can separate two payments that
         * are identical in amount, method and date, so a planned investigation
         * there buys a prettier trace and nothing else.
         *
         * Ordering changes no verdict. Each exception is investigated and
         * decided independently, and the idempotency key is a hash of the facts
         * rather than of position, so a re-run still suppresses duplicates.
         */
        out.exceptions.sort(Comparator
                .comparing((Exception2 e) -> e.ambiguous)
                .thenComparing(e -> -Math.abs(e.residue))
                .thenComparing(e -> e.settlementId));

        for (Exception2 x : out.exceptions) {
            x.trace = agent.run(x, setlById, reversed);
            x.type = x.trace.classification();
            x.hypothesis = x.trace.rationale();

            // The evidence digest binds the decision to the exact rows it was
            // based on, so a re-run over changed inputs is NOT suppressed as a
            // duplicate -- only a genuinely identical situation is.
            StringBuilder dig = new StringBuilder();
            for (var e : x.trace.evidence())
                dig.append(e.provenanceType()).append(':').append(e.provenanceId()).append(';');
            String evidenceDigest = Audit.sha256(dig.toString()).substring(0, 16);

            Policy.Decision dec = policy.evaluate(merchant, x.settlementId, x.type,
                    x.confidence, x.residue, x.batchValue, x.ambiguous,
                    x.valueDate, evidenceDigest);
            x.decision = dec;
            x.idempotencyKey = dec.idempotencyKey();
            if (dec.verdict() == Policy.Verdict.SUPPRESSED_DUPLICATE) {
                // Carry the ORIGINAL decision forward. The item is still
                // escalated or still auto-posted; the ledger simply refused to
                // record it a second time.
                String prior = audit.priorVerdict(dec.idempotencyKey());
                x.verdict = prior != null ? prior : dec.verdict().name();
                x.alreadyPosted = true;
                x.policyReason = (prior != null
                        ? "decided " + prior + " on an earlier run; "
                        : "") + dec.reason();
            } else {
                x.verdict = dec.verdict().name();
                x.policyReason = dec.reason();
            }

            Map<String, String> payload = new LinkedHashMap<>();
            payload.put("residue", Money.fmt(x.residue));
            payload.put("batch_value", Money.fmt(x.batchValue));
            payload.put("resolution", x.trace.resolution());
            payload.put("classification", x.type);
            payload.put("evidence_count", String.valueOf(x.trace.evidence().size()));
            payload.put("evidence_digest", evidenceDigest);
            payload.put("bank_txn_id", x.bankTxnId == null ? "" : x.bankTxnId);
            payload.put("agent_steps", String.join(">", x.trace.steps()));

            if (dec.verdict() != Policy.Verdict.SUPPRESSED_DUPLICATE)
                audit.append(dec.verdict() == Policy.Verdict.AUTO_POST ? "engine" : "agent",
                        dec.verdict() == Policy.Verdict.AUTO_POST
                                ? "auto_post_journal_entry" : "escalate_to_human",
                        "settlement", x.settlementId, dec.idempotencyKey(),
                        out.modelVersion, x.confidence, dec.verdict().name(), payload);
        }
        out.auditSuppressed = audit.suppressedDuplicates();
        out.auditHead = audit.head();
        out.auditRows = audit.appendedCount();
        for (Exception2 x : out.exceptions) {
            if (x.decision == null) continue;
            // Count by the EFFECTIVE verdict so a re-run still reports how many
            // items are auto-posted and how many await a human. `suppressed`
            // separately records how many postings the ledger refused.
            if ("AUTO_POST".equals(x.verdict)) out.autoPosted++;
            else if ("ESCALATE".equals(x.verdict)) out.escalated++;
            if (x.alreadyPosted) out.suppressed++;
        }
    }

    /** The solver's own words for why this batch was not resolved. */
    private static String refusalReason(Netting.Assignment na, String settlementId) {
        if (na != null)
            for (var r : na.refusals())
                if (r.settlementId().equals(settlementId))
                    return r.outcome() + " — " + r.reason();
        return "the partition solver produced no membership for this batch";
    }

    /** Aggregate the observable per-batch deductions the engine can derive. */
    private record Deductions(long refundNetted, long chargeback, long disputeFee,
                              long reversal, long reserveReleased, long reserveHeld) {}

    private void buildDecompositions(Output out, Netting.Assignment na) {
        Map<String, Settlement> setlById = new LinkedHashMap<>();
        for (Settlement s : in.settlements) setlById.put(s.settlementId(), s);
        Map<String, BankTxn> bankById = new LinkedHashMap<>();
        for (BankTxn b : in.bank) bankById.put(b.bankTxnId(), b);
        Map<String, String> settlementToBank = new LinkedHashMap<>();
        for (var e : out.bankToSettlement.entrySet()) settlementToBank.put(e.getValue(), e.getKey());

        Map<LocalDate, List<Window>> windows = cycleWindows(na, setlById);
        List<LocalDate> settleDates = new ArrayList<>();
        for (Settlement s2 : in.settlements)
            if (!settleDates.contains(s2.settleDate())) settleDates.add(s2.settleDate());
        settleDates.sort(Comparator.naturalOrder());
        Set<String> separate = settledSeparately();

        for (Settlement s : in.settlements) {
            Decomposition d = new Decomposition();
            d.settlementId = s.settlementId();
            String bankId = settlementToBank.get(s.settlementId());
            d.bankTxnId = bankId;
            BankTxn b = bankId == null ? null : bankById.get(bankId);
            d.bankAmount = b == null ? 0 : b.amount();
            d.confidence = bankId == null ? 0.0 : out.matchResult.confidence().getOrDefault(bankId, 0.0);

            List<Payment> members = na == null ? List.of()
                    : na.members().getOrDefault(s.settlementId(), List.of());
            for (Payment p : members) d.memberPaymentIds.add(p.paymentId());

            // Reconstructed gross from members; fall back to the reported gross
            // ONLY when netting was never run, so the ablation still produces a
            // number.
            //
            // The distinction matters more than it looks. When the solver RAN
            // and refused, falling back to the settlement's own reported gross
            // makes the batch reconcile to zero residue against a credit that
            // equals that same reported figure — so a batch whose membership
            // could not be proven reported itself perfectly reconciled, and the
            // refusal never reached anyone. The solver was safe and the
            // decomposition quietly overrode it.
            boolean membershipProven = !members.isEmpty();
            boolean solverRefused = (na != null) && members.isEmpty();
            long gross = members.isEmpty() ? s.gross() : FeeModel.gross(members);
            Model.FeeBreakdown fb = members.isEmpty()
                    ? new Model.FeeBreakdown(s.fees(), s.gst(), 0, 0)
                    : fees.forSet(members, s.instant());
            long reserveHeld = members.isEmpty() ? 0 : fees.reserveHeld(members, s.instant());

            Deductions ded = deductions(s, windows, settleDates, separate, out.unattributed);
            long tds = s.tds();   // TDS eligibility is not observable per payment

            long predicted = gross - fb.fee() - fb.gst() - tds
                    - ded.refundNetted() - ded.chargeback() - ded.disputeFee()
                    - fees.disputeGst(ded.disputeFee()) + ded.reversal()
                    - reserveHeld + ded.reserveReleased();

            d.components.put("gross", gross);
            d.components.put("platform_fee", -fb.fee());
            d.components.put("gst_on_fee", -fb.gst());
            d.components.put("tds_194o", -tds);
            d.components.put("refund_netted", -ded.refundNetted());
            d.components.put("chargeback_debit", -ded.chargeback());
            d.components.put("dispute_fee", -ded.disputeFee());
            d.components.put("dispute_fee_gst", -fees.disputeGst(ded.disputeFee()));
            d.components.put("chargeback_reversal", ded.reversal());
            d.components.put("reserve_held", -reserveHeld);
            d.components.put("reserve_released", ded.reserveReleased());

            // A settlement with no matched bank credit is NOT reconciled. Its
            // residue is the whole predicted payout: we cannot show the money
            // arrived. Scoring it as zero residue would let a matcher that
            // simply refuses to match everything post a perfect residue number.
            d.residue = (b == null) ? -predicted : b.amount() - predicted;
            d.membershipProven = membershipProven || na == null;
            d.unmatched = (b == null);
            // An unproven partition is never "within tolerance". Its gross came
            // from the platform's own report, which the credit was derived from,
            // so the residue closes to zero by construction and says nothing.
            d.withinTolerance = !d.unmatched && d.membershipProven
                    && Math.abs(d.residue) <= cfg.tolerancePaise;
            d.ambiguous = bankId != null && out.ambiguousBankTxns.contains(bankId);
            for (Payment p : members)
                if (out.ambiguousPayments.contains(p.paymentId())) { d.ambiguous = true; break; }
            if (b == null) d.note = "no bank credit matched to this settlement";
            else if (!d.membershipProven) {
                d.ambiguous = true;      // never auto-postable
                d.note = "membership unproven: " + refusalReason(na, s.settlementId());
            }

            out.decompositions.add(d);
            out.totalResidue += d.residue;
            out.absResidue += Math.abs(d.residue);
            out.grossMatched += gross;

            if (!d.withinTolerance || d.ambiguous) {
                Exception2 x = new Exception2();
                x.unmatched = d.unmatched;
                x.settlementId = s.settlementId();
                x.bankTxnId = bankId;
                x.residue = d.residue;
                x.batchValue = gross;
                x.confidence = d.confidence;
                x.ambiguous = d.ambiguous;
                x.valueDate = b == null ? s.settleDate() : b.valueDate();
                x.componentTrace = new LinkedHashMap<>(d.components);
                x.memberPaymentIds = new ArrayList<>(d.memberPaymentIds);
                if (!d.membershipProven)
                    x.partitionRefusal = refusalReason(na, s.settlementId());
                out.exceptions.add(x);
            }
        }
        out.decompositions.sort(Comparator.comparing(x -> x.settlementId));
    }

    /**
     * Cycle windows, derived -- not configured.
     *
     * Once Stage 5 has reconstructed batch membership, each batch on a
     * settlement date occupies a contiguous time-of-day window. Adjustments
     * (netted refunds, chargebacks, representment credits, reserve releases)
     * enter the same cycle machinery as payments, so an adjustment belongs to
     * the batch whose window contains its time of day.
     *
     * The engine discovers these windows from the data it reconstructed. It is
     * never told the platform's cycle count or cut times.
     */
    /**
     * One batch's slice of a settlement date.
     *
     * `obsLo..obsHi` is EVIDENCE: the first and last reconstructed member of the
     * batch actually traded at those times. `lo..hi` is the INFERRED boundary,
     * widened to cover the gaps between batches so the day is fully partitioned.
     *
     * Keeping them apart is the whole safety property. A timestamp inside the
     * observed range is bracketed by real payments and can be attributed on
     * evidence. A timestamp in the widened part sits in a gap where nothing
     * traded -- the boundary there is a hypothesis about where the platform cut
     * the cycle, and attributing on it is a guess wearing a timestamp.
     */
    record Window(String settlementId, int lo, int hi, int obsLo, int obsHi) {
        boolean observedBrackets(int sec) { return sec >= obsLo && sec <= obsHi; }
        boolean inferredContains(int sec) { return sec >= lo && sec <= hi; }
    }

    /**
     * The outcome of attributing one adjustment to a batch.
     *
     * `settlementId` is null unless deterministic evidence establishes ownership.
     * There is no "probable" owner: either a reconstructed member brackets the
     * timestamp or the adjustment is refused and surfaced for a human.
     */
    public record Attribution(String settlementId, String basis, List<String> candidates,
                              String reason) {
        boolean proven() { return settlementId != null; }
    }

    /** An adjustment the engine declined to attribute, with the reason. */
    public static final class UnattributedAdjustment {
        public String adjustmentId, adjustmentType, reason, basis;
        public long amountPaise;
        public LocalDate settleDate;
        public java.time.LocalDateTime stampedAt;
        public List<String> candidates = new ArrayList<>();
    }

    private Map<LocalDate, List<Window>> cycleWindows(Netting.Assignment na,
                                                      Map<String, Settlement> setlById) {
        Map<LocalDate, List<Window>> out = new LinkedHashMap<>();
        if (na == null) return out;
        for (var e : na.members().entrySet()) {
            Settlement s = setlById.get(e.getKey());
            if (s == null || e.getValue().isEmpty() || s.instant()) continue;
            int lo = Integer.MAX_VALUE, hi = Integer.MIN_VALUE;
            for (Payment p : e.getValue()) {
                int sec = p.createdAtIst().toLocalTime().toSecondOfDay();
                lo = Math.min(lo, sec);
                hi = Math.max(hi, sec);
            }
            out.computeIfAbsent(s.settleDate(), k -> new ArrayList<>())
               .add(new Window(s.settlementId(), lo, hi, lo, hi));
        }
        // Member timestamps only cover the part of a cycle that actually saw
        // traffic, so the raw ranges leave gaps at both ends of every window.
        // An adjustment stamped in a gap -- a reserve release at 06:03 when the
        // cycle happens to start trading at 06:10 -- would be attributed to the
        // previous cycle. Widening to midpoints is not enough, because the
        // midpoint sits inside the gap.
        //
        // Better hypothesis: settlement cycles cut the day into EQUAL time
        // divisions. Propose that, VERIFY every reconstructed member falls in
        // its own division, and only then use the clean boundaries. If
        // verification fails the platform batches some other way, and we fall
        // back to midpoint windows rather than forcing a rule that does not
        // hold.
        Map<LocalDate, List<Window>> covered = new LinkedHashMap<>();
        for (var e : out.entrySet()) {
            List<Window> l = new ArrayList<>(e.getValue());
            l.sort(Comparator.comparingInt(Window::lo));
            List<Window> uniform = uniformDivisions(l);
            covered.put(e.getKey(), uniform != null ? uniform : midpointWindows(l));
        }
        return covered;
    }

    /** Equal divisions of the day, accepted only if every member fits its own. */
    private List<Window> uniformDivisions(List<Window> l) {
        int k = l.size();
        if (k == 0 || 86400 % k != 0) return null;
        int width = 86400 / k;
        List<Window> out = new ArrayList<>();
        for (int i = 0; i < k; i++) {
            Window w = l.get(i);
            int lo = i * width, hi = (i + 1) * width - 1;
            if (w.lo() < lo || w.hi() > hi) return null;   // hypothesis refuted
            out.add(new Window(w.settlementId(), lo, hi, w.obsLo(), w.obsHi()));
        }
        return out;
    }

    /** Fallback: cut halfway between the observed ranges, covering the day. */
    private List<Window> midpointWindows(List<Window> l) {
        List<Window> w = new ArrayList<>();
        for (int i = 0; i < l.size(); i++) {
            int lo = (i == 0) ? 0 : (l.get(i - 1).hi() + l.get(i).lo() + 1) / 2;
            int hi = (i == l.size() - 1) ? 86399 : (l.get(i).hi() + l.get(i + 1).lo()) / 2;
            w.add(new Window(l.get(i).settlementId(), lo, hi,
                             l.get(i).obsLo(), l.get(i).obsHi()));
        }
        return w;
    }

    /**
     * The settlement date an adjustment attaches to.
     *
     * An adjustment raised on day D is due T+2, but if no settlement run falls
     * on that date (a holiday, say) the platform attaches it to the nearest run
     * instead. The engine hypothesises the same rule.
     */
    private LocalDate attachDate(LocalDate due, List<LocalDate> settleDates) {
        if (settleDates.contains(due)) return due;
        LocalDate best = null;
        long bestD = Long.MAX_VALUE;
        for (LocalDate d : settleDates) {
            long delta = Math.abs(java.time.temporal.ChronoUnit.DAYS.between(due, d));
            if (delta < bestD || (delta == bestD && best != null && d.isBefore(best))) {
                bestD = delta;
                best = d;
            }
        }
        return best;
    }

    /**
     * Which batch owns an adjustment stamped at this time of day, and why.
     *
     * THE RULE: attribute only when a reconstructed member of exactly one batch
     * brackets the timestamp. That is real evidence -- payments belonging to
     * that batch were being taken at that moment.
     *
     * What this deliberately no longer does is snap to the nearest window. The
     * previous implementation, when no window contained the timestamp, walked
     * the list and returned whichever boundary was closest, breaking ties by
     * list order. That silently attributed adjustments on the strength of a
     * boundary the engine had inferred rather than observed -- and a refund
     * attributed to the wrong batch moves money between two payouts that both
     * still balance, which is the failure mode nobody catches for weeks.
     */
    Attribution attribute(Map<LocalDate, List<Window>> windows, LocalDate date,
                                  java.time.LocalDateTime at) {
        List<Window> l = windows.get(date);
        if (l == null || l.isEmpty() || at == null)
            return new Attribution(null, "no_batches_on_date", List.of(),
                    "no reconstructed batch on this settlement date to attribute against");

        int sec = at.toLocalTime().toSecondOfDay();

        // Single batch on the date: nothing to confuse it with.
        if (l.size() == 1)
            return new Attribution(l.get(0).settlementId(), "sole_batch_on_date",
                    List.of(l.get(0).settlementId()), "only one batch settled on this date");

        List<String> bracketing = new ArrayList<>();
        for (Window w : l) if (w.observedBrackets(sec)) bracketing.add(w.settlementId());

        if (bracketing.size() == 1)
            return new Attribution(bracketing.get(0), "observed_members_bracket_timestamp",
                    bracketing, "reconstructed members of this batch were trading at that time");

        if (bracketing.size() > 1)
            return new Attribution(null, "overlapping_observed_ranges", bracketing,
                    "two batches were trading simultaneously at that time, so the timestamp "
                    + "does not identify one of them");

        // Nothing observed brackets it: the timestamp falls in a gap between
        // batches. Report which batches the INFERRED boundary would have chosen
        // between, so a reviewer can see exactly what was declined.
        List<String> near = new ArrayList<>();
        for (Window w : l) if (w.inferredContains(sec)) near.add(w.settlementId());
        if (near.isEmpty()) {
            int bestD = Integer.MAX_VALUE;
            for (Window w : l) {
                int d = sec < w.obsLo() ? w.obsLo() - sec : sec - w.obsHi();
                if (d < bestD) { bestD = d; near = new ArrayList<>(List.of(w.settlementId())); }
                else if (d == bestD) near.add(w.settlementId());
            }
        }
        return new Attribution(null, "timestamp_in_inferred_gap", near,
                "no batch was trading at that time; the only thing linking it to a batch is an "
                + "interpolated cycle boundary, which is a hypothesis rather than evidence");
    }

    /**
     * Deductions the engine derives from observable sources and attributes to a
     * specific batch via the derived cycle windows. Nothing here is estimated:
     * every term is a sum of amounts read from a source document.
     */
    private Deductions deductions(Settlement s, Map<LocalDate, List<Window>> windows,
                                  List<LocalDate> settleDates, Set<String> settledSeparately,
                                  List<UnattributedAdjustment> refused) {
        long refundNetted = 0, chargeback = 0, disputeFee = 0, reversal = 0, reserveReleased = 0;
        LocalDate sd = s.settleDate();
        String me = s.settlementId();

        for (Refund r : in.refunds) {
            if (!r.netted()) continue;
            LocalDate due = attachDate(
                    BankingCalendar.addBankingDays(r.createdAtIst().toLocalDate(), 2), settleDates);
            if (!sd.equals(due)) continue;
            var a = attribute(windows, sd, r.createdAtIst());
            if (a.proven()) { if (me.equals(a.settlementId())) refundNetted += r.amount(); }
            else record(refused, "refund", r.refundId(), r.amount(), sd, r.createdAtIst(), a);
        }
        for (Chargeback c : in.chargebacks) {
            LocalDate due = attachDate(
                    BankingCalendar.addBankingDays(c.raisedAtIst().toLocalDate(), 2), settleDates);
            if (sd.equals(due)) {
                var a = attribute(windows, sd, c.raisedAtIst());
                if (a.proven()) {
                    if (me.equals(a.settlementId())) {
                        // The dispute FEE is always netted out of the payout. The
                        // disputed AMOUNT is only netted when the bank statement
                        // does not already carry it as its own debit -- which is
                        // how the engine tells the two cases apart.
                        disputeFee += c.fee();
                        if (!settledSeparately.contains(c.disputeId())) chargeback += c.amount();
                    }
                } else record(refused, "chargeback", c.disputeId(),
                              c.amount() + c.fee(), sd, c.raisedAtIst(), a);
            }
            if (c.reversed()) {
                LocalDate rd = attachDate(c.reversedAtIst().toLocalDate(), settleDates);
                if (!sd.equals(rd)) continue;
                var a = attribute(windows, sd, c.reversedAtIst());
                if (a.proven()) { if (me.equals(a.settlementId())) reversal += c.amount(); }
                else record(refused, "chargeback_reversal", c.disputeId(),
                            c.amount(), sd, c.reversedAtIst(), a);
            }
        }
        for (Model.ReserveEntry e : in.reserve) {
            if (!e.netted()) continue;
            LocalDate rd = attachDate(e.releaseDate(), settleDates);
            if (!sd.equals(rd)) continue;
            var a = attribute(windows, sd, e.releasedAtIst());
            if (a.proven()) { if (me.equals(a.settlementId())) reserveReleased += e.amount(); }
            else record(refused, "reserve_release", e.reserveId(), e.amount(),
                        sd, e.releasedAtIst(), a);
        }
        return new Deductions(refundNetted, chargeback, disputeFee, reversal, reserveReleased, 0);
    }

    /**
     * Record a refusal once per adjustment.
     *
     * deductions() runs per settlement, so an unattributable adjustment is seen
     * once for every batch on its date. It is one refusal, not N, and recording
     * it N times would inflate the count and read as N separate problems.
     */
    private void record(List<UnattributedAdjustment> refused, String type, String id,
                        long amount, LocalDate sd, java.time.LocalDateTime at, Attribution a) {
        for (UnattributedAdjustment u : refused)
            if (u.adjustmentId.equals(id) && u.adjustmentType.equals(type)) return;
        UnattributedAdjustment u = new UnattributedAdjustment();
        u.adjustmentType = type; u.adjustmentId = id; u.amountPaise = amount;
        u.settleDate = sd; u.stampedAt = at; u.basis = a.basis();
        u.reason = a.reason(); u.candidates = a.candidates();
        refused.add(u);
    }

    /**
     * Entities that appear on the bank statement as their OWN debit row, found
     * by the reference token the narration carries. Anything in this set was
     * settled separately and must NOT also be netted out of a payout, or it
     * would be double counted.
     */
    private Set<String> settledSeparately() {
        Set<String> out = new HashSet<>();
        for (BankTxn b : in.bank) {
            if (b.credit()) continue;
            out.addAll(Normalizer.parse(b.bankTxnId(), b.narration()).refTokens());
        }
        return out;
    }
}
