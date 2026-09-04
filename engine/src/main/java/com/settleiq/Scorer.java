package com.settleiq;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * Stage 3 -- calibrated pair scorer.
 *
 * The model is TRAINED in Python (mlservice/train.py) on the train split only,
 * then exported as plain JSON: a list of depth-limited regression trees plus an
 * isotonic calibration map. Java loads and evaluates it.
 *
 * Exporting the model instead of calling a service at scoring time is
 * deliberate. Reconciliation must be reproducible and auditable: the same batch
 * re-run six months later has to produce the same score, which means the model
 * is a versioned artifact pinned in the audit ledger, not a live endpoint whose
 * weights may have moved.
 *
 * If no model file is present the scorer falls back to a transparent
 * deterministic prior, so the deterministic-only ablation row still runs.
 */
public final class Scorer {

    /** node arrays: feature index, threshold, left, right, leaf value */
    record Tree(int[] feat, double[] thr, int[] left, int[] right, double[] leaf) {
        double eval(double[] x) {
            int n = 0;
            while (left[n] >= 0) n = x[feat[n]] <= thr[n] ? left[n] : right[n];
            return leaf[n];
        }
    }

    private final List<Tree> trees = new ArrayList<>();
    private double bias;
    private double[] isoX, isoY;
    private final boolean loaded;
    public final String modelVersion;

    public Scorer(Path modelJson) {
        if (modelJson == null || !Files.exists(modelJson)) {
            loaded = false;
            modelVersion = "deterministic-prior-v1";
            return;
        }
        @SuppressWarnings("unchecked")
        Map<String, Object> m = (Map<String, Object>) Csv.jsonFile(modelJson);
        this.bias = (Double) m.get("bias");
        this.modelVersion = (String) m.getOrDefault("version", "unversioned");
        @SuppressWarnings("unchecked")
        List<Object> ts = (List<Object>) m.get("trees");
        for (Object o : ts) {
            @SuppressWarnings("unchecked")
            Map<String, Object> t = (Map<String, Object>) o;
            trees.add(new Tree(ints(t.get("feature")), dbls(t.get("threshold")),
                    ints(t.get("left")), ints(t.get("right")), dbls(t.get("value"))));
        }
        @SuppressWarnings("unchecked")
        Map<String, Object> iso = (Map<String, Object>) m.get("isotonic");
        this.isoX = dbls(iso.get("x"));
        this.isoY = dbls(iso.get("y"));
        loaded = true;
    }

    public boolean isLoaded() { return loaded; }

    @SuppressWarnings("unchecked")
    private static int[] ints(Object o) {
        List<Object> l = (List<Object>) o;
        int[] a = new int[l.size()];
        for (int i = 0; i < a.length; i++) a[i] = (int) (double) (Double) l.get(i);
        return a;
    }

    @SuppressWarnings("unchecked")
    private static double[] dbls(Object o) {
        List<Object> l = (List<Object>) o;
        double[] a = new double[l.size()];
        for (int i = 0; i < a.length; i++) a[i] = (Double) l.get(i);
        return a;
    }

    /** Raw margin, before calibration. */
    public double raw(double[] x) {
        if (!loaded) return prior(x);
        double s = bias;
        for (Tree t : trees) s += t.eval(x);
        return s;
    }

    /** Calibrated probability in [0,1]. */
    public double probability(double[] x) {
        double r = raw(x);
        if (!loaded) return clamp(sigmoid(r));
        return clamp(isotonic(sigmoid(r)));
    }

    /**
     * Deterministic fallback prior. Transparent by design: an exact UTR is
     * near-certain, a repaired UTR strong, and otherwise the amount delta and
     * banking-day distance carry it. Used for the deterministic-only ablation.
     */
    private static double prior(double[] x) {
        double s = -3.0;
        if (x[7] > 0) s += 8.0;                 // utr_exact
        if (x[8] > 0) s += 4.5 * x[9];          // utr_repaired * confidence
        if (x[2] > 0) s += 2.5;                 // delta within tolerance
        s -= 0.35 * x[5];                       // abs banking-day delta
        s -= 0.9 * Math.min(x[0], 20);          // log amount delta
        s += 1.2 * x[10];                       // trigram overlap
        s += 1.0 * x[11];                       // jaro-winkler
        return s;
    }

    private double isotonic(double p) {
        if (isoX == null || isoX.length == 0) return p;
        if (p <= isoX[0]) return isoY[0];
        if (p >= isoX[isoX.length - 1]) return isoY[isoY.length - 1];
        int lo = 0, hi = isoX.length - 1;
        while (hi - lo > 1) {
            int mid = (lo + hi) >>> 1;
            if (isoX[mid] <= p) lo = mid; else hi = mid;
        }
        double t = (p - isoX[lo]) / Math.max(isoX[hi] - isoX[lo], 1e-12);
        return isoY[lo] + t * (isoY[hi] - isoY[lo]);
    }

    static double sigmoid(double z) { return 1.0 / (1.0 + Math.exp(-z)); }
    static double clamp(double p) { return Math.max(1e-6, Math.min(1 - 1e-6, p)); }
}
