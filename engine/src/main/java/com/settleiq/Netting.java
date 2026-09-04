package com.settleiq;

import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

import com.settleiq.Model.Payment;
import com.settleiq.Model.Settlement;

/**
 * Stage 5 -- netting decomposition.
 *
 * For each settlement batch, reconstruct WHICH payments compose it. Batch
 * membership is never published; only the batch total is. So this is a
 * constrained k-way exact partition of the payments due on a settlement date
 * among the batches settling that date, with each batch's gross as a hard
 * constraint.
 *
 * Two solvers, tried in order:
 *
 *  H1  ORDERED-BLOCK. Hypothesise that a settlement cycle is a contiguous
 *      window over some ordering key, then VERIFY arithmetically that prefix
 *      sums hit every batch gross exactly. O(n log n). This is a hypothesis the
 *      engine proposes and checks, not a rule it was told -- if the platform
 *      batched some other way, verification fails and we fall through.
 *
 *  H2  BUCKETED SUBSET-SUM DP over paise with pruning, per batch, on the
 *      residual pool. Exponential in the worst case, so it is bounded by item
 *      count and target width and gives up rather than running forever.
 *
 * Anything neither solver settles becomes an exception. Giving up is a
 * supported outcome; guessing is not.
 */
public final class Netting {

    /** One batch the partition solver declined to resolve, and why. */
    public record Refusal(String settlementId, String outcome, String reason,
                          int poolSize, long targetPaise, int statesExplored, long micros) {}

    public record Assignment(Map<String, List<Payment>> members,
                             Set<String> ambiguousPaymentIds,
                             Map<String, String> solverUsed,
                             List<String> unassignedPaymentIds,
                             int h1Dates, int h2Batches, int failedDates,
                             List<String> failureDiagnostics,
                             List<Refusal> refusals,
                             Map<String, Integer> solverOutcomes) {}

    private final FeeModel fees;
    private final boolean allowDp;
    private final int dpMaxItems;

    public Netting(FeeModel fees, boolean allowDp, int dpMaxItems) {
        this.fees = fees;
        this.allowDp = allowDp;
        this.dpMaxItems = dpMaxItems;
    }

    /** Ordering keys the engine is willing to hypothesise, most likely first. */
    private static final List<Comparator<Payment>> ORDER_KEYS = List.of(
            // settlement cycles are usually cut on time-of-day across all the
            // capture dates that collapse onto one settlement date
            Comparator.<Payment>comparingInt(p -> p.createdAtIst().toLocalTime().toSecondOfDay())
                    .thenComparing(Payment::paymentId),
            // ...but a platform may equally cut on the raw capture instant
            Comparator.comparing(Payment::createdAtIst, Comparator.naturalOrder())
                    .thenComparing(Payment::paymentId),
            Comparator.comparing(Payment::paymentId));

    public Assignment solve(List<Payment> payments, List<Settlement> settlements) {
        // Pool payments by the settlement date implied by the T+2 banking rule.
        Map<LocalDate, List<Payment>> pool = new LinkedHashMap<>();
        Map<LocalDate, List<Payment>> sameDay = new LinkedHashMap<>();
        for (Payment p : payments) {
            if (!p.captured()) continue;
            LocalDate due = BankingCalendar.addBankingDays(p.captureDate(), 2);
            pool.computeIfAbsent(due, k -> new ArrayList<>()).add(p);
            sameDay.computeIfAbsent(p.captureDate(), k -> new ArrayList<>()).add(p);
        }

        Map<LocalDate, List<Settlement>> byDate = new LinkedHashMap<>();
        for (Settlement s : settlements)
            byDate.computeIfAbsent(s.settleDate(), k -> new ArrayList<>()).add(s);

        Map<String, List<Payment>> members = new LinkedHashMap<>();
        Map<String, String> solver = new LinkedHashMap<>();
        Set<String> assigned = new HashSet<>();
        int h1 = 0, h2 = 0, failed = 0;
        List<Refusal> refusals = new ArrayList<>();
        Map<String, Integer> outcomes = new LinkedHashMap<>();
        List<String> diag = new ArrayList<>();

        for (LocalDate d : sorted(byDate.keySet())) {
            List<Settlement> batches = new ArrayList<>(byDate.get(d));
            batches.sort(Comparator.comparing(Settlement::settlementId));

            // An instant (T+0) batch settles same-day captures and MUST be
            // resolved before the T+2 pass. Otherwise those payments are
            // counted twice -- once here, and again in the T+2 pool two days
            // later -- and every pool total stops closing.
            List<Settlement> instant = batches.stream().filter(Settlement::instant).toList();
            List<Settlement> regular = batches.stream().filter(s -> !s.instant()).toList();

            List<Group> groups = List.of(
                    new Group(instant, sameDay.getOrDefault(d, List.of()), true),
                    new Group(regular, pool.getOrDefault(d, List.of()), false));

            for (Group grp : groups) {
                if (grp.batches().isEmpty()) continue;
                List<Payment> avail = new ArrayList<>();
                for (Payment p : grp.candidates())
                    if (!assigned.contains(p.paymentId())) avail.add(p);
                if (avail.isEmpty()) {
                    failed++;
                    diag.add(d + " " + (grp.prefixOk() ? "instant" : "regular")
                            + ": no unassigned payments available for "
                            + grp.batches().size() + " batch(es)");
                    continue;
                }
                long poolSum = 0;
                for (Payment p : avail) poolSum += p.amount();
                long wantSum = 0;
                for (Settlement s2 : grp.batches()) wantSum += s2.gross();

                Map<String, List<Payment>> got = h1OrderedBlock(avail, grp.batches(), grp.prefixOk());
                if (got != null) {
                    h1++;
                    for (var e : got.entrySet()) {
                        members.put(e.getKey(), e.getValue());
                        solver.put(e.getKey(), "H1_ordered_block");
                        for (Payment p : e.getValue()) assigned.add(p.paymentId());
                    }
                    continue;
                }

                if (allowDp) {
                    boolean any = false;
                    List<Payment> remaining = new ArrayList<>(avail);
                    for (Settlement s : grp.batches()) {
                        Solution sol = h2SubsetSum(remaining, s.gross());
                        outcomes.merge(sol.outcome().name(), 1, Integer::sum);
                        if (sol.proven()) {
                            members.put(s.settlementId(), sol.subset());
                            solver.put(s.settlementId(), "H2_subset_sum_dp");
                            h2++;
                            any = true;
                            for (Payment p : sol.subset()) assigned.add(p.paymentId());
                            remaining.removeAll(sol.subset());
                        } else {
                            // A refusal is a result, not a gap. Record which one,
                            // so the exception queue can say whether the answer
                            // was impossible, ambiguous, or merely out of budget.
                            refusals.add(new Refusal(s.settlementId(), sol.outcome().name(),
                                    sol.reason(), remaining.size(), s.gross(),
                                    sol.statesExplored(), sol.micros()));
                        }
                    }
                    if (!any) {
                        failed++;
                        diag.add(d + " " + (grp.prefixOk() ? "instant" : "regular")
                                + ": H1 and DP both failed; pool=" + Money.fmt(poolSum)
                                + " batches=" + Money.fmt(wantSum)
                                + " delta=" + Money.fmt(poolSum - wantSum)
                                + " items=" + avail.size() + " nbatches=" + grp.batches().size());
                    }
                } else {
                    failed++;
                    diag.add(d + " " + (grp.prefixOk() ? "instant" : "regular")
                            + ": H1 failed, DP disabled; delta=" + Money.fmt(poolSum - wantSum));
                }
            }
        }

        // Swap-invariance: two equal-amount, equal-method payments landing in
        // different batches on the same date are not separable by arithmetic.
        Set<String> ambiguous = detectSwapInvariant(members, settlements);

        List<String> unassigned = new ArrayList<>();
        for (Payment p : payments)
            if (p.captured() && !assigned.contains(p.paymentId()))
                unassigned.add(p.paymentId());

        return new Assignment(members, ambiguous, solver, unassigned, h1, h2, failed, diag,
                              refusals, outcomes);
    }

    /**
     * A set of batches plus the payment pool they may legally draw from.
     * prefixOk marks an instant sweep, which settles a PREFIX of the day and
     * legitimately leaves the remainder to the T+2 cycle.
     */
    private record Group(List<Settlement> batches, List<Payment> candidates, boolean prefixOk) {}

    private static boolean contains(List<Payment> l, Payment p) {
        for (Payment x : l) if (x.paymentId().equals(p.paymentId())) return true;
        return false;
    }

    private static List<LocalDate> sorted(Set<LocalDate> s) {
        List<LocalDate> l = new ArrayList<>(s);
        l.sort(Comparator.naturalOrder());
        return l;
    }

    /**
     * H1: try each ordering key; under that order, walk the pool accumulating a
     * running sum and cut whenever it exactly equals the next batch's gross.
     * Succeeds only if EVERY batch is satisfied and the pool is fully consumed.
     */
    private Map<String, List<Payment>> h1OrderedBlock(List<Payment> avail,
                                                      List<Settlement> batches,
                                                      boolean prefixOk) {
        long poolTotal = 0;
        for (Payment p : avail) poolTotal += p.amount();
        long batchTotal = 0;
        for (Settlement s : batches) batchTotal += s.gross();
        // The pool must close exactly. An instant sweep is the one exception:
        // it consumes a prefix of the day and leaves the rest to T+2.
        if (prefixOk ? poolTotal < batchTotal : poolTotal != batchTotal) return null;

        for (Comparator<Payment> key : ORDER_KEYS) {
            List<Payment> sortedPool = new ArrayList<>(avail);
            sortedPool.sort(key);
            // batch order must also be tried: cycle ids ascend with the key
            for (boolean reverse : new boolean[]{false, true}) {
                List<Settlement> bs = new ArrayList<>(batches);
                bs.sort(Comparator.comparing(Settlement::settlementId));
                if (reverse) java.util.Collections.reverse(bs);
                Map<String, List<Payment>> out = new LinkedHashMap<>();
                int i = 0;
                boolean ok = true;
                for (Settlement s : bs) {
                    long acc = 0;
                    List<Payment> cur = new ArrayList<>();
                    while (i < sortedPool.size() && acc < s.gross()) {
                        Payment p = sortedPool.get(i++);
                        acc += p.amount();
                        cur.add(p);
                    }
                    if (acc != s.gross()) { ok = false; break; }
                    out.put(s.settlementId(), cur);
                }
                if (ok && (i == sortedPool.size() || prefixOk)) return out;
            }
        }
        return null;
    }

    /**
     * H2: exact subset-sum for one batch gross over the residual pool.
     *
     * Bucketed DP keyed on reachable sums, capped hard on both item count and
     * state count. Returns null (refuse) rather than approximating.
     */
    /** Why the subset-sum solver returned what it did. Never conflated. */
    public enum Outcome {
        UNIQUE,                          // exactly one subset sums to the target
        AMBIGUOUS_MULTIPLE_PARTITIONS,   // two or more do; none is provable
        NO_EXACT_SUBSET,                 // the target is not reachable at all
        BOUND_ITEMS,                     // pool larger than the verified item cap
        BOUND_STATES,                    // reachable-sum states exceeded the cap
        BOUND_TIME                       // wall-clock budget exhausted
    }

    /**
     * A solve attempt, with its outcome and the work it took.
     *
     * `subset` is non-null ONLY for UNIQUE. Every other outcome is a refusal
     * carrying a reason the exception queue can show a human, because "the
     * solver returned nothing" is three completely different situations and an
     * operator needs to know which one they are looking at.
     */
    public record Solution(List<Payment> subset, Outcome outcome, String reason,
                           int statesExplored, long micros) {
        public boolean proven() { return outcome == Outcome.UNIQUE && subset != null; }
    }

    /**
     * H2: exact subset-sum for one batch gross over the residual pool.
     *
     * THE SAFETY PROPERTY: it does not stop at the first subset that fits.
     *
     * A previous version returned as soon as any combination hit the target,
     * which silently picked one arbitrary explanation whenever several were
     * arithmetically valid — and on a batch of similar amounts several usually
     * are. Two different sets of payments summing to the same gross are
     * observationally identical; choosing between them is a guess that moves
     * real payments into the wrong payout while every total still ties out.
     *
     * So the DP counts solutions with a saturating counter and stops at two.
     * One is provable and gets returned; two or more is an ambiguity and gets
     * refused. Counting to two costs the same asymptotically as counting to one
     * and is the difference between a proof and a coincidence.
     */
    Solution h2SubsetSum(List<Payment> items, long target) {
        long t0 = System.nanoTime();
        if (target <= 0 || items.isEmpty())
            return refuse(Outcome.NO_EXACT_SUBSET, "empty pool or non-positive target", 0, t0);
        if (items.size() > dpMaxItems)
            return refuse(Outcome.BOUND_ITEMS, "pool of " + items.size()
                    + " payments exceeds the verified bound of " + dpMaxItems, 0, t0);
        long total = 0;
        for (Payment p : items) total += p.amount();
        if (total < target)
            return refuse(Outcome.NO_EXACT_SUBSET, "the whole pool sums to less than the target",
                    0, t0);

        // reachable sum -> first item that reached it, and the sum it came from
        Map<Long, Integer> parentItem = new HashMap<>();
        Map<Long, Long> parentSum = new HashMap<>();
        // reachable sum -> how many distinct subsets reach it, saturating at 2
        Map<Long, Integer> ways = new HashMap<>();
        parentItem.put(0L, -1);
        parentSum.put(0L, -1L);
        ways.put(0L, 1);

        final int MAX_STATES = 250_000;
        final long deadline = System.currentTimeMillis() + 1500;

        for (int idx = 0; idx < items.size(); idx++) {
            long a = items.get(idx).amount();
            // Snapshot: a 0/1 knapsack must not let one item be used twice, so
            // the additions of this pass are folded in only after it completes.
            Map<Long, Integer> add = new HashMap<>();
            for (var e : ways.entrySet()) {
                long ns = e.getKey() + a;
                if (ns > target) continue;
                add.merge(ns, e.getValue(), (x, y) -> Math.min(2, x + y));
                if (!parentItem.containsKey(ns)) {
                    parentItem.put(ns, idx);
                    parentSum.put(ns, e.getKey());
                }
            }
            for (var e : add.entrySet())
                ways.merge(e.getKey(), e.getValue(), (x, y) -> Math.min(2, x + y));

            // Bounded on state count AND wall time. An exact solver that hangs
            // on one pathological batch is worse than one that refuses and hands
            // the batch to the exception queue.
            if (ways.size() > MAX_STATES)
                return refuse(Outcome.BOUND_STATES, "reachable-sum states exceeded "
                        + MAX_STATES + "; the search space is larger than the verified bound",
                        ways.size(), t0);
            if (System.currentTimeMillis() > deadline)
                return refuse(Outcome.BOUND_TIME,
                        "solver exceeded its 1500 ms budget before proving a unique partition",
                        ways.size(), t0);
        }

        int n = ways.getOrDefault(target, 0);
        if (n == 0)
            return refuse(Outcome.NO_EXACT_SUBSET,
                    "no combination of these payments sums to the batch gross", ways.size(), t0);
        if (n >= 2)
            return refuse(Outcome.AMBIGUOUS_MULTIPLE_PARTITIONS,
                    "at least two different combinations of these payments sum to the batch "
                    + "gross exactly; the arithmetic does not identify which one the platform "
                    + "actually batched", ways.size(), t0);

        List<Payment> subset = reconstruct(items, parentItem, parentSum, target);
        if (subset == null)
            return refuse(Outcome.NO_EXACT_SUBSET, "solution could not be reconstructed",
                    ways.size(), t0);
        return new Solution(subset, Outcome.UNIQUE, "exactly one combination sums to the gross",
                ways.size(), (System.nanoTime() - t0) / 1000);
    }

    private static Solution refuse(Outcome o, String why, int states, long t0) {
        return new Solution(null, o, why, states, (System.nanoTime() - t0) / 1000);
    }

    /** Walk the parent chain back to zero. Each step uses a strictly earlier
     *  item, so no payment can appear twice in the recovered subset. */
    private static List<Payment> reconstruct(List<Payment> items, Map<Long, Integer> pi,
                                             Map<Long, Long> ps, long target) {
        List<Payment> out = new ArrayList<>();
        long cur = target;
        while (cur > 0) {
            Integer idx = pi.get(cur);
            if (idx == null || idx < 0) return null;
            out.add(items.get(idx));
            cur = ps.get(cur);
        }
        return out;
    }

    /**
     * Two payments with the same amount AND the same method, assigned to
     * different batches that settle on the same date, can be swapped without
     * changing either batch's gross OR either batch's fee (same method and same
     * rate tier means the same fee). No arithmetic separates them. Both are
     * flagged, and the policy engine must refuse to auto-post them.
     */
    private Set<String> detectSwapInvariant(Map<String, List<Payment>> members,
                                            List<Settlement> settlements) {
        Map<String, LocalDate> settleDate = new HashMap<>();
        for (Settlement s : settlements) settleDate.put(s.settlementId(), s.settleDate());

        // (settleDate, amount, method, rateTierDate) -> settlements it appears in
        Map<String, Map<String, List<String>>> idx = new HashMap<>();
        for (var e : members.entrySet()) {
            LocalDate d = settleDate.get(e.getKey());
            if (d == null) continue;
            for (Payment p : e.getValue()) {
                var tier = fees.card().tierFor(p.method(), p.captureDate());
                String key = d + "|" + p.amount() + "|" + p.method() + "|" + tier.effectiveFrom();
                idx.computeIfAbsent(key, k -> new LinkedHashMap<>())
                        .computeIfAbsent(e.getKey(), k -> new ArrayList<>())
                        .add(p.paymentId());
            }
        }
        Set<String> amb = new HashSet<>();
        for (var e : idx.entrySet())
            if (e.getValue().size() > 1)            // spans >1 batch on the same date
                for (var l : e.getValue().values()) amb.addAll(l);
        return amb;
    }
}
