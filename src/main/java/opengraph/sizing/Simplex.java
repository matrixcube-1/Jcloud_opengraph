package opengraph.sizing;

/**
 * Simplex primal tableau — max c.x s.t. A.x <= b, x >= 0, b >= 0.
 * Remplacement interne de la couche HiGHS du document (JCloud ne peut
 * pas embarquer de natif). Regle de Bland (premier indice negatif) ->
 * deterministe, anti-cyclage.
 */
public final class Simplex {

    public static final class Result {
        public final boolean optimal;
        public final double objective;
        public final double[] x;

        Result(boolean optimal, double objective, double[] x) {
            this.optimal = optimal;
            this.objective = objective;
            this.x = x;
        }
    }

    private Simplex() {}

    public static Result maximize(double[] c, double[][] A, double[] b) {
        int m = A.length;
        int n = c.length;
        for (double bi : b) {
            if (bi < 0.0) {
                return new Result(false, 0.0, new double[n]);
            }
        }
        // tableau (m+1) x (n+m+1)
        double[][] t = new double[m + 1][n + m + 1];
        for (int i = 0; i < m; i++) {
            System.arraycopy(A[i], 0, t[i], 0, n);
            t[i][n + i] = 1.0;
            t[i][n + m] = b[i];
        }
        for (int j = 0; j < n; j++) {
            t[m][j] = -c[j];
        }

        int maxIt = 10_000;
        while (maxIt-- > 0) {
            // colonne entrante : premier cout reduit negatif (Bland)
            int e = -1;
            for (int j = 0; j < n + m; j++) {
                if (t[m][j] < -1e-10) {
                    e = j;
                    break;
                }
            }
            if (e < 0) {
                break; // optimal
            }
            // ligne sortante : ratio minimal
            int l = -1;
            double best = Double.POSITIVE_INFINITY;
            for (int i = 0; i < m; i++) {
                if (t[i][e] > 1e-12) {
                    double r = t[i][n + m] / t[i][e];
                    if (r < best - 1e-12) {
                        best = r;
                        l = i;
                    }
                }
            }
            if (l < 0) {
                return new Result(false, Double.POSITIVE_INFINITY,
                        new double[n]); // non borne
            }
            pivot(t, l, e, m, n + m + 1);
        }
        double[] x = new double[n];
        for (int j = 0; j < n; j++) {
            // variable basique si sa colonne est unitaire
            int row = -1;
            boolean unit = true;
            for (int i = 0; i < m; i++) {
                if (Math.abs(t[i][j]) > 1e-9) {
                    if (row >= 0 || Math.abs(t[i][j] - 1.0) > 1e-9) {
                        unit = false;
                        break;
                    }
                    row = i;
                }
            }
            x[j] = (unit && row >= 0) ? t[row][n + m] : 0.0;
        }
        return new Result(true, t[m][n + m], x);
    }

    private static void pivot(double[][] t, int l, int e, int m, int w) {
        double pe = t[l][e];
        for (int j = 0; j < w; j++) {
            t[l][j] /= pe;
        }
        for (int i = 0; i <= m; i++) {
            if (i == l) {
                continue;
            }
            double f = t[i][e];
            if (Math.abs(f) < 1e-18) {
                continue;
            }
            for (int j = 0; j < w; j++) {
                t[i][j] -= f * t[l][j];
            }
        }
    }
}
