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

import com.settleiq.Model.BankTxn;
import com.settleiq.Model.Candidate;
import com.settleiq.Model.Settlement;

/**
 * Stages 1-4: link each payout bank credit to a settlement record.
 *
 *  1  exact  -- narration carries the settlement's UTR verbatim
 *  2  blocked candidate generation -- optimise RECALL here; precision is
 *     Stage 3/4's job. Blocking on a fee-model-aware amount window plus a
 *     banking-day window keeps the candidate graph small enough for Hungarian.
 *  3  calibrated pair scorer
 *  4  global one-to-one assignment (Hungarian), never greedy per row
 */
public final class Matcher {

    public record Result(Map<String, String> bankToSettlement,   // bank_txn -> settlement
                         Map<String, Double> confidence,
                         Map<String, String> matchSource,
                         List<Candidate> allCandidates,
                         Set<String> ambiguousBankTxns,
                         int exactCount, int candidatePairs) {}

    private final Config cfg;
    private final Scorer scorer;

    public Matcher(Config cfg, Scorer scorer) { this.cfg = cfg; this.scorer = scorer; }

    /**
     * Bank rows that cancel each other: a credit later reversed by a debit of
     * the same amount carrying the same reference. Returns the ids of BOTH
     * rows -- the credit never really landed, and the debit is not a business
     * event of its own.
     */
    static Set<String> washedOut(List<BankTxn> bank) {
        Set<String> out = new HashSet<>();
        List<BankTxn> debits = new ArrayList<>();
        for (BankTxn b : bank) if (!b.credit()) debits.add(b);
        for (BankTxn d : debits) {
            String n = d.narration().toUpperCase();
            boolean looksLikeReturn = n.contains("RETURN") || n.contains("REVERSAL")
                    || n.contains("REVERSED");
            if (!looksLikeReturn) continue;
            var dp = Normalizer.parse(d.bankTxnId(), d.narration());
            for (BankTxn c : bank) {
                if (!c.credit() || out.contains(c.bankTxnId())) continue;
                if (c.amount() != -d.amount()) continue;
                long days = Math.abs(java.time.temporal.ChronoUnit.DAYS
                        .between(c.valueDate(), d.valueDate()));
                if (days > 5) continue;
                var cp = Normalizer.parse(c.bankTxnId(), c.narration());
                boolean sharedRef = false;
                for (String x : cp.utrCandidates())
                    if (dp.utrCandidates().contains(x)) { sharedRef = true; break; }
                if (!sharedRef) continue;
                out.add(c.bankTxnId());
                out.add(d.bankTxnId());
                break;
            }
        }
        return out;
    }

    public Result match(List<BankTxn> bank, List<Settlement> settlements,
                        Map<String, Normalizer.Parsed> parsedByTxn) {

        // DUPLICATE-CREDIT PROTECTION.
        //
        // A failed payout appears as a credit, then a return debit, then a
        // retry credit under a NEW utr. All three rows carry the same amount.
        // Matching the settlement to the reversed credit looks perfectly
        // plausible on amount and date, and is wrong: that money came back out.
        // Identify credit/debit wash pairs first and take both rows out of
        // contention, so only the retry can win.
        Set<String> reversed = washedOut(bank);

        List<BankTxn> credits = new ArrayList<>();
        for (BankTxn b : bank) if (b.credit() && !reversed.contains(b.bankTxnId())) credits.add(b);

        Set<String> knownUtrs = new HashSet<>();
        for (Settlement s : settlements) if (s.utr() != null && !s.utr().isBlank()) knownUtrs.add(s.utr());
        Map<String, Settlement> byUtr = new HashMap<>();
        for (Settlement s : settlements)
            if (s.utr() != null && !s.utr().isBlank()) byUtr.put(s.utr(), s);

        Map<String, Normalizer.Repair> repairs = new HashMap<>();
        if (cfg.stageUtrRepair)
            for (BankTxn b : credits) {
                Normalizer.Parsed p = parsedByTxn.get(b.bankTxnId());
                Normalizer.Repair r = Normalizer.repair(p.utrCandidates(), knownUtrs);
                if (r != null) repairs.put(b.bankTxnId(), r);
            }

        // ---- Stage 1: exact UTR --------------------------------------------
        Map<String, String> assigned = new LinkedHashMap<>();
        Map<String, Double> conf = new LinkedHashMap<>();
        Map<String, String> source = new LinkedHashMap<>();
        Set<String> takenSettlements = new HashSet<>();
        int exact = 0;

        if (cfg.stageExactMatch) {
            for (BankTxn b : credits) {
                Normalizer.Parsed p = parsedByTxn.get(b.bankTxnId());
                for (String c : p.utrCandidates()) {
                    Settlement s = byUtr.get(c);
                    if (s == null || takenSettlements.contains(s.settlementId())) continue;
                    assigned.put(b.bankTxnId(), s.settlementId());
                    conf.put(b.bankTxnId(), 1.0);
                    source.put(b.bankTxnId(), "exact_utr");
                    takenSettlements.add(s.settlementId());
                    exact++;
                    break;
                }
            }
        }

        // ---- Stage 1b: UNIQUE UTR REPAIR is identity evidence ---------------
        //
        // A UTR that repairs to exactly one known settlement identifies that
        // settlement. It is not a statistical guess and must not be gated on
        // the amount agreeing.
        //
        // This matters more than it looks. The learned scorer leans on amount
        // delta, because for almost every batch the credit equals the net. So
        // the ONE batch with a genuine discrepancy scores lowest and would be
        // left unmatched -- and an unmatched batch is one we never explain.
        // That is exactly backwards: the batches where the money disagrees are
        // the ones a controller most needs linked.
        //
        // Identity evidence (the UTR) establishes the link. Amount evidence
        // establishes the RESIDUE. Keeping those two jobs separate is what lets
        // a discrepancy surface as an exception instead of vanishing.
        if (cfg.stageUtrRepair) {
            Map<String, List<String>> claimedBy = new LinkedHashMap<>();
            for (BankTxn b : credits) {
                if (assigned.containsKey(b.bankTxnId())) continue;
                Normalizer.Repair r = repairs.get(b.bankTxnId());
                if (r == null) continue;
                Settlement s = byUtr.get(r.utr());
                if (s == null || takenSettlements.contains(s.settlementId())) continue;
                claimedBy.computeIfAbsent(s.settlementId(), k -> new ArrayList<>())
                         .add(b.bankTxnId());
            }
            for (var e : claimedBy.entrySet()) {
                // two credits repairing onto the same settlement is not
                // identity evidence any more; leave both to the scorer
                if (e.getValue().size() != 1) continue;
                String bid = e.getValue().get(0);
                Normalizer.Repair r = repairs.get(bid);
                assigned.put(bid, e.getKey());
                conf.put(bid, r.confidence());
                source.put(bid, "repaired_utr:" + r.method());
                takenSettlements.add(e.getKey());
            }
        }

        // ---- Stage 2: candidate generation ---------------------------------
        List<BankTxn> open = new ArrayList<>();
        for (BankTxn b : credits) if (!assigned.containsKey(b.bankTxnId())) open.add(b);
        List<Settlement> free = new ArrayList<>();
        for (Settlement s : settlements)
            if (!takenSettlements.contains(s.settlementId())) free.add(s);

        List<Candidate> cands = new ArrayList<>();
        Map<String, List<Candidate>> byBank = new LinkedHashMap<>();
        if (cfg.stageCandidateGen) {
            for (BankTxn b : open) {
                Normalizer.Parsed p = parsedByTxn.get(b.bankTxnId());
                Normalizer.Repair rep = repairs.get(b.bankTxnId());
                List<Candidate> mine = new ArrayList<>();
                for (Settlement s : free) {
                    int bd = Math.abs(BankingCalendar.between(s.settleDate(), b.valueDate()));
                    if (bd > cfg.dateWindowBankingDays) continue;
                    long delta = Math.abs(b.amount() - s.net());
                    boolean utrHit = rep != null && rep.utr().equals(s.utr());
                    // widen the amount gate when a repaired UTR already points here
                    if (!utrHit && delta > cfg.amountWindowPaise) continue;
                    Candidate c = new Candidate(b.bankTxnId(), s.settlementId(),
                            utrHit ? "repaired_utr" : "blocked");
                    c.features = Features.build(b, s, p, rep, cfg.tolerancePaise, 0.0);
                    cands.add(c);
                    mine.add(c);
                }
                byBank.put(b.bankTxnId(), mine);
            }
        }

        // ---- Stage 3: score -------------------------------------------------
        for (Candidate c : cands) {
            c.rawScore = scorer.raw(c.features);
            c.probability = scorer.probability(c.features);
        }
        // second pass: rank-gap feature needs the peer set, then rescore
        for (var e : byBank.entrySet()) {
            List<Candidate> l = new ArrayList<>(e.getValue());
            l.sort(Comparator.comparingDouble((Candidate c) -> -c.probability));
            double top = l.isEmpty() ? 0 : l.get(0).probability;
            double second = l.size() > 1 ? l.get(1).probability : 0;
            for (Candidate c : l) {
                c.features[Features.NAMES.length - 1] = top - second;
                c.rawScore = scorer.raw(c.features);
                c.probability = scorer.probability(c.features);
            }
        }

        // ---- Stage 4: global assignment -------------------------------------
        Set<String> ambiguous = new HashSet<>();
        if (cfg.stageGlobalAssignment && !open.isEmpty() && !free.isEmpty()) {
            Map<String, Integer> rowOf = new LinkedHashMap<>();
            Map<String, Integer> colOf = new LinkedHashMap<>();
            for (int i = 0; i < open.size(); i++) rowOf.put(open.get(i).bankTxnId(), i);
            for (int j = 0; j < free.size(); j++) colOf.put(free.get(j).settlementId(), j);

            long[][] cost = new long[open.size()][free.size()];
            for (long[] row : cost) java.util.Arrays.fill(row, Hungarian.INF);
            for (Candidate c : cands) {
                int i = rowOf.get(c.bankTxnId), j = colOf.get(c.settlementId);
                // scaled negative log-likelihood: integer costs keep this reproducible
                cost[i][j] = Math.round(-Math.log(Math.max(c.probability, 1e-9)) * 1_000_000.0);
            }
            int[] a = Hungarian.solve(cost);
            for (int i = 0; i < a.length; i++) {
                if (a[i] < 0) continue;
                BankTxn b = open.get(i);
                Settlement s = free.get(a[i]);
                double p = 0;
                for (Candidate c : byBank.getOrDefault(b.bankTxnId(), List.of()))
                    if (c.settlementId.equals(s.settlementId())) p = c.probability;
                if (p < cfg.minAssignProbability) continue;
                assigned.put(b.bankTxnId(), s.settlementId());
                conf.put(b.bankTxnId(), p);
                source.put(b.bankTxnId(), repairs.containsKey(b.bankTxnId())
                        && repairs.get(b.bankTxnId()).utr().equals(s.utr())
                        ? "repaired_utr" : "global_assignment");
            }
        } else if (cfg.stageCandidateGen) {
            // ablation: greedy per-row argmax, deliberately worse, to show the
            // cost of not solving the assignment globally
            for (var e : byBank.entrySet()) {
                Candidate best = null;
                for (Candidate c : e.getValue())
                    if (best == null || c.probability > best.probability) best = c;
                if (best == null || best.probability < cfg.minAssignProbability) continue;
                if (takenSettlements.contains(best.settlementId)) continue;
                assigned.put(e.getKey(), best.settlementId);
                conf.put(e.getKey(), best.probability);
                source.put(e.getKey(), "greedy");
                takenSettlements.add(best.settlementId);
            }
        }

        // near-tie detection: two settlements almost equally plausible
        for (var e : byBank.entrySet()) {
            List<Candidate> l = new ArrayList<>(e.getValue());
            l.sort(Comparator.comparingDouble((Candidate c) -> -c.probability));
            if (l.size() > 1 && l.get(0).probability > 0
                    && (l.get(0).probability - l.get(1).probability) < cfg.ambiguityMargin
                    && !"exact_utr".equals(source.get(e.getKey())))
                ambiguous.add(e.getKey());
        }

        return new Result(assigned, conf, source, cands, ambiguous, exact, cands.size());
    }
}
