package com.settleiq;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Stage 0 -- narration parsing, UTR extraction and repair.
 *
 * Rule-based and deterministic. Every extracted field carries the character
 * span that produced it, so a downstream claim can always be traced back to the
 * exact substring of the bank statement it came from. The exception agent is
 * forbidden from stating a fact without such provenance.
 */
public final class Normalizer {

    /** One extracted field with the character span that produced it. */
    public record Span(String field, String value, int start, int end, String rule) {}

    public record Parsed(String bankTxnId, String raw, String canonical,
                         String utrExact, List<String> utrCandidates,
                         Set<String> refTokens, String rail, List<Span> provenance) {}

    // UTR shapes seen on Indian rails.
    private static final Pattern P_NEFT = Pattern.compile("\\b([A-Z]{4}[NR]\\d{1,2}\\d{8}\\d{4,8})\\b");
    private static final Pattern P_NEFT_LOOSE = Pattern.compile("([A-Z]{4}\\s?[NR]\\s?\\d[\\s]?\\d{6,18})");
    private static final Pattern P_NUM12 = Pattern.compile("\\b(\\d{12})\\b");
    private static final Pattern P_NUM_LONG = Pattern.compile("\\b(\\d{9,18})\\b");
    private static final Pattern P_ALNUM16 = Pattern.compile("\\b([A-Z0-9]{14,22})\\b");
    // A 12-digit IMPS/UPI reference that picked up keying confusions (O for 0,
    // S for 5) is no longer all digits, so the numeric patterns miss it
    // entirely and no candidate is produced at all. Accept mostly-numeric
    // mixed tokens of plausible length and let confusion repair map them back.
    private static final Pattern P_MIXED = Pattern.compile("\\b([A-Z0-9]{10,13})\\b");
    private static final Pattern P_REF = Pattern.compile("\\b((?:rfnd|disp|pay|setl|rsrv)_[0-9a-zA-Z]+)\\b",
            Pattern.CASE_INSENSITIVE);

    private static final Map<Character, Character> UNCONFUSE = new LinkedHashMap<>();
    static {
        // Bank keying/OCR confusions run both ways; we generate both variants.
        UNCONFUSE.put('O', '0'); UNCONFUSE.put('I', '1'); UNCONFUSE.put('S', '5');
        UNCONFUSE.put('B', '8'); UNCONFUSE.put('Z', '2');
    }

    /** Uppercase, collapse whitespace, unify separators. Reversible enough for spans. */
    public static String canonical(String s) {
        return s == null ? "" : s.toUpperCase().replaceAll("\\s+", " ").trim();
    }

    public static Parsed parse(String bankTxnId, String raw) {
        String c = canonical(raw);
        List<Span> prov = new ArrayList<>();
        List<String> cands = new ArrayList<>();
        Set<String> seen = new LinkedHashSet<>();

        String rail = detectRail(c);
        if (!rail.isEmpty()) prov.add(new Span("rail", rail, 0, Math.min(c.length(), 8), "prefix_token"));

        // 1. structured NEFT/RTGS style, strict then loose (loose repairs split_utr)
        addAll(P_NEFT, c, "utr", "neft_rtgs_strict", cands, seen, prov);
        Matcher m = P_NEFT_LOOSE.matcher(c);
        while (m.find()) {
            String joined = m.group(1).replace(" ", "");
            if (seen.add(joined)) {
                cands.add(joined);
                prov.add(new Span("utr", joined, m.start(1), m.end(1), "neft_rtgs_despaced"));
            }
        }
        // 2. numeric rails
        addAll(P_NUM12, c, "utr", "imps_upi_12digit", cands, seen, prov);
        addAll(P_NUM_LONG, c, "utr", "numeric_long", cands, seen, prov);
        // 3. bare alphanumeric block
        addAll(P_ALNUM16, c, "utr", "alnum_block", cands, seen, prov);
        // 4. mostly-numeric mixed token: a numeric rail reference with keying damage
        Matcher mx = P_MIXED.matcher(c);
        while (mx.find()) {
            String v = mx.group(1);
            int digits = 0;
            for (char ch : v.toCharArray()) if (Character.isDigit(ch)) digits++;
            if (digits < 8 || digits == v.length()) continue;   // pure digits already handled
            if (seen.add(v)) {
                cands.add(v);
                prov.add(new Span("utr", v, mx.start(1), mx.end(1), "mixed_numeric_damaged"));
            }
        }

        // reference tokens (rfnd_/disp_/pay_) -- these give exact entity links
        Set<String> refs = new LinkedHashSet<>();
        Matcher r = P_REF.matcher(raw);
        while (r.find()) {
            refs.add(r.group(1).toLowerCase());
            prov.add(new Span("reference", r.group(1).toLowerCase(), r.start(1), r.end(1), "entity_token"));
        }

        String exact = cands.isEmpty() ? null : cands.get(0);
        return new Parsed(bankTxnId, raw, c, exact, cands, refs, rail, prov);
    }

    private static void addAll(Pattern p, String c, String field, String rule,
                               List<String> out, Set<String> seen, List<Span> prov) {
        Matcher m = p.matcher(c);
        while (m.find()) {
            String v = m.group(1);
            if (seen.add(v)) {
                out.add(v);
                prov.add(new Span(field, v, m.start(1), m.end(1), rule));
            }
        }
    }

    private static String detectRail(String c) {
        if (c.startsWith("NEFT")) return "neft";
        if (c.startsWith("IMPS")) return "imps";
        if (c.startsWith("RTGS")) return "rtgs";
        if (c.startsWith("UPI")) return "upi_payout";
        if (c.startsWith("CMS")) return "corp";
        return "";
    }

    /**
     * Repair a candidate against the set of known settlement UTRs.
     *
     * Repairs attempted, in order of decreasing confidence:
     *   1. exact hit
     *   2. de-spaced hit (split_utr)
     *   3. unique prefix hit (truncate_utr) -- REFUSED if more than one match
     *   4. character-confusion un-mapping (charswap_utr), unique match only
     *
     * A repair that maps to more than one known UTR is discarded, not guessed.
     * That refusal is the whole point: a wrong UTR repair produces a false match
     * that costs an analyst hours to unwind.
     */
    public record Repair(String utr, String method, double confidence) {}

    public static Repair repair(List<String> candidates, Set<String> knownUtrs) {
        for (String c : candidates)
            if (knownUtrs.contains(c)) return new Repair(c, "exact", 1.0);

        for (String c : candidates) {
            String d = c.replace(" ", "");
            if (knownUtrs.contains(d)) return new Repair(d, "despaced", 0.98);
        }

        for (String c : candidates) {
            if (c.length() < 8) continue;
            List<String> hits = new ArrayList<>();
            for (String k : knownUtrs) if (k.startsWith(c)) hits.add(k);
            if (hits.size() == 1) return new Repair(hits.get(0), "prefix_unique", 0.94);
            if (hits.size() > 1) return null;   // ambiguous prefix: refuse
        }

        for (String c : candidates) {
            if (c.length() < 8) continue;
            Set<String> variants = confusionVariants(c);
            List<String> hits = new ArrayList<>();
            for (String k : knownUtrs) if (variants.contains(k)) hits.add(k);
            if (hits.size() == 1) return new Repair(hits.get(0), "confusion_unique", 0.90);
            if (hits.size() > 1) return null;
            // truncated AND confused
            for (String v : variants) {
                if (v.length() < 8) continue;
                List<String> ph = new ArrayList<>();
                for (String k : knownUtrs) if (k.startsWith(v)) ph.add(k);
                if (ph.size() == 1) return new Repair(ph.get(0), "confusion_prefix", 0.86);
            }
        }
        return null;
    }

    /** All strings reachable by un-applying up to 3 character confusions. */
    static Set<String> confusionVariants(String s) {
        Set<String> out = new LinkedHashSet<>();
        out.add(s);
        for (int round = 0; round < 3; round++) {
            Set<String> next = new LinkedHashSet<>(out);
            for (String cur : out) {
                char[] a = cur.toCharArray();
                for (int i = 0; i < a.length; i++) {
                    Character to = UNCONFUSE.get(a[i]);
                    Character back = null;
                    for (var e : UNCONFUSE.entrySet()) if (e.getValue() == a[i]) back = e.getKey();
                    if (to != null) { char o = a[i]; a[i] = to; next.add(new String(a)); a[i] = o; }
                    if (back != null) { char o = a[i]; a[i] = back; next.add(new String(a)); a[i] = o; }
                }
            }
            if (next.size() == out.size()) break;
            out = next;
            if (out.size() > 4000) break;      // hard cap: never explode
        }
        return out;
    }
}
