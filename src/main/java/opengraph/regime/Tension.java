package opengraph.regime;

/**
 * Tension multi-sauts T — port de tension.rs (conception §2).
 *
 * Chaine : log-taux mids x (antisym.) -> potentiel LS p ((L+J/n)p = b)
 * -> residu R (composante cyclique de Hodge) -> adjacence A = |R|/s
 * -> Abar = D^(-1/2) A D^(-1/2) -> Atilde = Abar + Abar^2 + Abar^3
 * -> M = D^(1/2) Atilde D^(1/2) -> T = ||M||_F / (L*n).
 *
 * Mat8 = double[8][8], Vec8 = double[8].
 */
public final class Tension {

    private Tension() {}

    static final double N = 8.0;

    /** Erreurs de calcul de regime (fail-closed en amont). */
    public enum RegimeError {
        /** Graphe de cotation non connexe : p non identifiable. */
        DISCONNECTED,
        /** Entree non finie. */
        NON_FINITE
    }

    /** Connexite du graphe d'aretes par parcours en largeur. */
    static boolean isConnected(double[][] edges) {
        boolean[] seen = new boolean[8];
        int start = -1;
        for (int i = 0; i < 8 && start < 0; i++) {
            for (int j = 0; j < 8; j++) {
                if (edges[i][j] > 0.0) {
                    start = i;
                    break;
                }
            }
        }
        if (start < 0) {
            return false;
        }
        int[] stack = new int[8];
        int top = 0;
        stack[top++] = start;
        seen[start] = true;
        while (top > 0) {
            int i = stack[--top];
            for (int j = 0; j < 8; j++) {
                if (edges[i][j] > 0.0 && !seen[j]) {
                    seen[j] = true;
                    stack[top++] = j;
                }
            }
        }
        for (int i = 0; i < 8; i++) {
            boolean incident = false;
            for (int j = 0; j < 8; j++) {
                if (edges[i][j] > 0.0 || edges[j][i] > 0.0) {
                    incident = true;
                    break;
                }
            }
            if (incident && !seen[i]) {
                return false;
            }
        }
        return true;
    }

    /**
     * Potentiel des moindres carres : unique solution de (L + J/n) p = b,
     * b_i = somme_j x_ij sur aretes cotees. Cholesky 8x8.
     * Retourne null si NonFinite/Disconnected — appelant fail-closed.
     */
    public static double[] potential(double[][] x, double[][] edges) {
        for (int i = 0; i < 8; i++) {
            for (int j = 0; j < 8; j++) {
                if (!Double.isFinite(x[i][j])) {
                    return null;
                }
            }
        }
        if (!isConnected(edges)) {
            return null;
        }
        double[] b = new double[8];
        for (int i = 0; i < 8; i++) {
            double s = 0.0;
            for (int j = 0; j < 8; j++) {
                if (edges[i][j] > 0.0) {
                    s += x[i][j];
                }
            }
            b[i] = s;
        }
        double[][] m = LinAlg.fromFn((i, j) -> {
            double deg = 0.0;
            for (int k = 0; k < 8; k++) {
                if (edges[i][k] > 0.0) {
                    deg += 1.0;
                }
            }
            double l = i.equals(j) ? deg : -edges[i][j];
            return l + 1.0 / N;
        });
        return LinAlg.choleskySolve(m, b);
    }

    /** Residu antisymetrique : R_ij = x_ij - (p_i - p_j) sur aretes. */
    public static double[][] residual(double[][] x, double[][] edges) {
        double[] p = potential(x, edges);
        if (p == null) {
            return null;
        }
        return LinAlg.fromFn((i, j) ->
                edges[i][j] > 0.0 ? x[i][j] - (p[i] - p[j]) : 0.0);
    }

    /** Adjacence de tension : A_ij = |R_ij| / s_ij sur les aretes. */
    public static double[][] adjacency(double[][] r, double[][] sNorm,
                                       double[][] edges) {
        return LinAlg.fromFn((i, j) ->
                edges[i][j] > 0.0 && sNorm[i][j] > 0.0
                        ? Math.abs(r[i][j]) / sNorm[i][j]
                        : 0.0);
    }

    /** Resultat de normalize : matrice normalisee + degres. */
    public static final class Normalized {
        public final double[][] abar;
        public final double[] degrees;

        Normalized(double[][] abar, double[] degrees) {
            this.abar = abar;
            this.degrees = degrees;
        }
    }

    /**
     * Abar = D^(-1/2) A D^(-1/2) avec garde : d_i <= eps -> inv_sqrt 0.
     */
    public static Normalized normalize(double[][] a, double eps) {
        double[] d = new double[8];
        for (int i = 0; i < 8; i++) {
            double s = 0.0;
            for (int j = 0; j < 8; j++) {
                s += a[i][j];
            }
            d[i] = s;
        }
        double[] invSqrt = new double[8];
        for (int i = 0; i < 8; i++) {
            invSqrt[i] = d[i] > eps ? 1.0 / Math.sqrt(d[i]) : 0.0;
        }
        double[][] abar = LinAlg.fromFn((i, j) ->
                invSqrt[i] * a[i][j] * invSqrt[j]);
        return new Normalized(abar, d);
    }

    /** Atilde = Abar + Abar^2 + ... + Abar^L par Horner (L-1 produits). */
    public static double[][] multiHop(double[][] abar, int l) {
        double[][] id = LinAlg.identity();
        double[][] acc = id;
        for (int k = 1; k < l; k++) {
            acc = LinAlg.add(id, LinAlg.mul(abar, acc));
        }
        return LinAlg.mul(abar, acc);
    }

    /** T = ||D^(1/2) Atilde D^(1/2)||_F / (L*n). */
    public static double tensionFromAdjacency(double[][] a, int l, double eps) {
        Normalized nd = normalize(a, eps);
        double[][] at = multiHop(nd.abar, l);
        double[] d = nd.degrees;
        double[] sq = new double[8];
        for (int i = 0; i < 8; i++) {
            sq[i] = d[i] > eps ? Math.sqrt(d[i]) : 0.0;
        }
        double[][] m = LinAlg.fromFn((i, j) ->
                sq[i] * at[i][j] * sq[j]);
        return LinAlg.norm(m) / (l * N);
    }
}
