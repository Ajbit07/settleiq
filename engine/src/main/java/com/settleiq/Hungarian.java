package com.settleiq;

/**
 * Stage 4 -- global assignment. O(n^3) Jonker-Volgenant style Hungarian
 * algorithm on a rectangular cost matrix.
 *
 * Why not greedy: a greedy per-row argmax will happily assign the same
 * settlement to two bank credits, or take a locally attractive pair that
 * strands a credit which had exactly one viable partner. Global assignment
 * makes the one-to-one constraint structural instead of a post-hoc repair.
 *
 * Costs are longs (scaled negative log-probabilities), never doubles, so the
 * result is bit-for-bit reproducible across runs and platforms.
 */
public final class Hungarian {
    private Hungarian() {}

    public static final long INF = Long.MAX_VALUE / 4;

    /**
     * @param cost rows x cols, cost[i][j] = INF means forbidden
     * @return assignment[i] = column assigned to row i, or -1
     */
    public static int[] solve(long[][] cost) {
        int n = cost.length;
        if (n == 0) return new int[0];
        int m = cost[0].length;
        if (m == 0) { int[] a = new int[n]; java.util.Arrays.fill(a, -1); return a; }

        int dim = Math.max(n, m);
        long[][] c = new long[dim + 1][dim + 1];
        for (long[] row : c) java.util.Arrays.fill(row, 0L);
        for (int i = 0; i < dim; i++)
            for (int j = 0; j < dim; j++)
                c[i + 1][j + 1] = (i < n && j < m) ? cost[i][j] : 0L;

        long[] u = new long[dim + 1], v = new long[dim + 1];
        int[] p = new int[dim + 1], way = new int[dim + 1];

        for (int i = 1; i <= dim; i++) {
            p[0] = i;
            int j0 = 0;
            long[] minv = new long[dim + 1];
            boolean[] used = new boolean[dim + 1];
            java.util.Arrays.fill(minv, INF);
            do {
                used[j0] = true;
                int i0 = p[j0], j1 = -1;
                long delta = INF;
                for (int j = 1; j <= dim; j++) {
                    if (used[j]) continue;
                    long cur = c[i0][j] - u[i0] - v[j];
                    if (cur < minv[j]) { minv[j] = cur; way[j] = j0; }
                    if (minv[j] < delta) { delta = minv[j]; j1 = j; }
                }
                if (j1 < 0) break;
                for (int j = 0; j <= dim; j++) {
                    if (used[j]) { u[p[j]] += delta; v[j] -= delta; }
                    else minv[j] -= delta;
                }
                j0 = j1;
            } while (p[j0] != 0);
            if (j0 == 0) continue;
            do {
                int j1 = way[j0];
                p[j0] = p[j1];
                j0 = j1;
            } while (j0 != 0);
        }

        int[] assign = new int[n];
        java.util.Arrays.fill(assign, -1);
        for (int j = 1; j <= dim; j++) {
            int i = p[j];
            if (i >= 1 && i <= n && j <= m) {
                if (cost[i - 1][j - 1] >= INF) continue;   // forbidden pair: leave unassigned
                assign[i - 1] = j - 1;
            }
        }
        return assign;
    }
}
