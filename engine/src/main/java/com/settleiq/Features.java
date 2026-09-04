package com.settleiq;

import java.util.HashSet;
import java.util.List;
import java.util.Set;

import com.settleiq.Model.BankTxn;
import com.settleiq.Model.Settlement;

/**
 * Feature engineering for the (bank credit, settlement) pair scorer.
 *
 * The feature vector is built in Java and in Python from this same ordered
 * list, so training and serving cannot drift. NAMES is the contract.
 */
public final class Features {
    private Features() {}

    public static final String[] NAMES = {
            "abs_amount_delta_log",     // log1p |bank - settlement.net|, in paise
            "signed_delta_sign",        // -1 / 0 / +1
            "delta_within_tolerance",   // |delta| <= tolerance
            "delta_rel_to_net",         // |delta| / max(net,1)
            "banking_day_delta",        // signed banking days, value_date vs settled_at
            "abs_banking_day_delta",
            "same_day",
            "utr_exact",                // narration carries the settlement UTR verbatim
            "utr_repaired",             // recovered by prefix/confusion repair
            "utr_repair_confidence",
            "token_overlap",            // Jaccard over alphanumeric tokens
            "jaro_winkler",             // on the canonical narration vs utr
            "narration_has_any_utr",
            "settlement_utr_present",   // settlement record actually carries a UTR
            "is_instant",
            "amount_rank_gap"           // how far the runner-up candidate sits
    };

    public static int size() { return NAMES.length; }

    public static double[] build(BankTxn b, Settlement s, Normalizer.Parsed parsed,
                                 Normalizer.Repair repair, long tolerance, double rankGap) {
        long delta = b.amount() - s.net();
        long ad = Math.abs(delta);
        int bd = BankingCalendar.between(s.settleDate(), b.valueDate());
        boolean exact = parsed.utrCandidates().contains(s.utr()) && !s.utr().isBlank();
        boolean repaired = !exact && repair != null && repair.utr().equals(s.utr());

        double[] f = new double[NAMES.length];
        int i = 0;
        f[i++] = Math.log1p(ad);
        f[i++] = Long.signum(delta);
        f[i++] = ad <= tolerance ? 1 : 0;
        f[i++] = (double) ad / Math.max(Math.abs(s.net()), 1);
        f[i++] = bd;
        f[i++] = Math.abs(bd);
        f[i++] = b.valueDate().equals(s.settleDate()) ? 1 : 0;
        f[i++] = exact ? 1 : 0;
        f[i++] = repaired ? 1 : 0;
        f[i++] = repair == null ? 0 : repair.confidence();
        f[i++] = tokenOverlap(parsed.canonical(), s.utr());
        f[i++] = jaroWinkler(bestToken(parsed, s.utr()), s.utr() == null ? "" : s.utr());
        f[i++] = parsed.utrCandidates().isEmpty() ? 0 : 1;
        f[i++] = (s.utr() == null || s.utr().isBlank()) ? 0 : 1;
        f[i++] = s.instant() ? 1 : 0;
        f[i++] = rankGap;
        return f;
    }

    static String bestToken(Normalizer.Parsed p, String utr) {
        if (utr == null || utr.isBlank() || p.utrCandidates().isEmpty()) return "";
        String best = p.utrCandidates().get(0);
        double bs = -1;
        for (String c : p.utrCandidates()) {
            double s = jaroWinkler(c, utr);
            if (s > bs) { bs = s; best = c; }
        }
        return best;
    }

    static double tokenOverlap(String narration, String utr) {
        if (utr == null || utr.isBlank()) return 0;
        Set<String> a = trigrams(narration.replaceAll("[^A-Z0-9]", ""));
        Set<String> b = trigrams(utr);
        if (a.isEmpty() || b.isEmpty()) return 0;
        Set<String> inter = new HashSet<>(a);
        inter.retainAll(b);
        Set<String> uni = new HashSet<>(a);
        uni.addAll(b);
        return (double) inter.size() / uni.size();
    }

    static Set<String> trigrams(String s) {
        Set<String> out = new HashSet<>();
        for (int i = 0; i + 3 <= s.length(); i++) out.add(s.substring(i, i + 3));
        return out;
    }

    /** Jaro-Winkler similarity, 0..1. */
    public static double jaroWinkler(String a, String b) {
        if (a == null) a = "";
        if (b == null) b = "";
        if (a.isEmpty() && b.isEmpty()) return 1;
        if (a.isEmpty() || b.isEmpty()) return 0;
        int la = a.length(), lb = b.length();
        int window = Math.max(la, lb) / 2 - 1;
        if (window < 0) window = 0;
        boolean[] ma = new boolean[la], mb = new boolean[lb];
        int matches = 0;
        for (int i = 0; i < la; i++) {
            int lo = Math.max(0, i - window), hi = Math.min(lb, i + window + 1);
            for (int j = lo; j < hi; j++) {
                if (mb[j] || a.charAt(i) != b.charAt(j)) continue;
                ma[i] = mb[j] = true;
                matches++;
                break;
            }
        }
        if (matches == 0) return 0;
        int t = 0, k = 0;
        for (int i = 0; i < la; i++) {
            if (!ma[i]) continue;
            while (!mb[k]) k++;
            if (a.charAt(i) != b.charAt(k)) t++;
            k++;
        }
        double half = t / 2.0;
        double jaro = (matches / (double) la + matches / (double) lb
                + (matches - half) / matches) / 3.0;
        int prefix = 0;
        for (int i = 0; i < Math.min(4, Math.min(la, lb)); i++) {
            if (a.charAt(i) == b.charAt(i)) prefix++; else break;
        }
        return jaro + prefix * 0.1 * (1 - jaro);
    }
}
