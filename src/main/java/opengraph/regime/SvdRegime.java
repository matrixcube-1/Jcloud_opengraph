package opengraph.regime;

import java.util.ArrayDeque;
import java.util.Deque;

/**
 * Dominance spectrale D et devise dominante c* — port de svd_regime.rs.
 *
 * y_t = p_t - p_(t-1) (rendements panier, 1'y = 0), fenetre W.
 * SVD de Y centree en temps : D = sigma1^2 / Somme sigma_k^2 ;
 * c* = argmax u1_i^2 si max >= q_min, sinon -1.
 *
 * La SVD nalgebra est remplacee par l'eigendecomposition Jacobi du
 * gramien Y_c Y_c^T : sigma_k^2 = lambda_k, u1 = 1er vecteur propre —
 * equivalent mathematique (memes tests de tolerance).
 */
public final class SvdRegime {

    /** Valeurs singulieres triees decroissant (8). */
    public final double[] sigma;
    /** D = sigma1^2 / Somme sigma_k^2 dans [1/rank, 1]. */
    public final double dominance;
    /** c* = argmax u1_i^2 si >= qMin, sinon -1. */
    public final int dominant;
    /** max_i u1_i^2 (le critere de c*). */
    public final double loadingSq;
    /** sgn(Somme y_tau,c*) sur rendements NON centres. */
    public final int direction;

    private SvdRegime(double[] sigma, double dominance, int dominant,
                      double loadingSq, int direction) {
        this.sigma = sigma;
        this.dominance = dominance;
        this.dominant = dominant;
        this.loadingSq = loadingSq;
        this.direction = direction;
    }

    private static SvdRegime build(double[] sigma, double[] u1,
                                   double[] rawSum, double qMin) {
        double energy = 0.0;
        for (double s : sigma) {
            energy += s * s;
        }
        if (!(energy > 0.0)) {
            return null;
        }
        int c = 0;
        double l2 = -1.0;
        for (int i = 0; i < 8; i++) {
            if (u1[i] * u1[i] > l2) {
                l2 = u1[i] * u1[i];
                c = i;
            }
        }
        return new SvdRegime(sigma, sigma[0] * sigma[0] / energy,
                l2 >= qMin ? c : -1, l2,
                rawSum[c] >= 0.0 ? 1 : -1);
    }

    /**
     * SVD directe de reference (audit/tests).
     * y = colonnes de rendements bruts p-diff (8 x W) ; centrage interne.
     * yCols[t][i] — W colonnes de 8 composantes.
     */
    public static SvdRegime dominanceFromReturns(double[][] yCols,
                                               double qMin) {
        int w = yCols.length;
        if (w < 2) {
            return null;
        }
        // centrage en temps : mean[i] = moyenne_t y[i][t]
        double[] mean = new double[8];
        for (double[] y : yCols) {
            if (y == null || y.length != 8) {
                return null;
            }
            for (int i = 0; i < 8; i++) {
                if (!Double.isFinite(y[i])) {
                    return null;
                }
                mean[i] += y[i];
            }
        }
        for (int i = 0; i < 8; i++) {
            mean[i] /= w;
        }
        // gramien centre : G = Somme_t (y_t - mean)(y_t - mean)^T
        double[][] g = LinAlg.zeros();
        double[] rawSum = new double[8];
        for (double[] y : yCols) {
            for (int i = 0; i < 8; i++) {
                rawSum[i] += y[i];
            }
            for (int i = 0; i < 8; i++) {
                double di = y[i] - mean[i];
                for (int j = 0; j < 8; j++) {
                    g[i][j] += di * (y[j] - mean[j]);
                }
            }
        }
        LinAlg.Eig eig = LinAlg.jacobiEigen(g, 1e-18, 100);
        double[] sigma = new double[8];
        for (int k = 0; k < 8; k++) {
            sigma[k] = Math.sqrt(Math.max(eig.values[k], 0.0));
        }
        double[] u1 = new double[8];
        for (int i = 0; i < 8; i++) {
            u1[i] = eig.vectors[i][0];
        }
        return build(sigma, u1, rawSum, qMin);
    }

    /** Gramien glissant : Gbrut = Somme yy^T et S = Somme y sur fenetre W. */
    public static class RollingGram {
        public final int w;
        private double[][] gbrut = LinAlg.zeros();
        private double[] s = new double[8];
        private final Deque<double[]> buf = new ArrayDeque<>();
        private int sinceExact;
        /** Recalcul exact du gramien tous les K echantillons. */
        public final int recomputeEvery;

        public RollingGram(int w, int recomputeEvery) {
            this.w = w;
            this.recomputeEvery = Math.max(1, recomputeEvery);
        }

        /** Pousse un echantillon ; retire le plus ancien si fenetre pleine. */
        public void push(double[] y) {
            if (buf.size() == w) {
                double[] old = buf.pollFirst();
                for (int i = 0; i < 8; i++) {
                    s[i] -= old[i];
                    for (int j = 0; j < 8; j++) {
                        gbrut[i][j] -= old[i] * old[j];
                    }
                }
            }
            for (int i = 0; i < 8; i++) {
                s[i] += y[i];
                for (int j = 0; j < 8; j++) {
                    gbrut[i][j] += y[i] * y[j];
                }
            }
            buf.addLast(y.clone());
            sinceExact++;
            if (sinceExact >= recomputeEvery && !buf.isEmpty()) {
                // Recalcul exact a instants deterministes.
                gbrut = LinAlg.zeros();
                s = new double[8];
                for (double[] v : buf) {
                    for (int i = 0; i < 8; i++) {
                        s[i] += v[i];
                        for (int j = 0; j < 8; j++) {
                            gbrut[i][j] += v[i] * v[j];
                        }
                    }
                }
                sinceExact = 0;
            }
        }

        /** Gramien centre G = Gbrut - SS^T/W (null si fenetre non pleine). */
        public double[][] gram() {
            if (buf.size() < w) {
                return null;
            }
            double[][] g = LinAlg.zeros();
            for (int i = 0; i < 8; i++) {
                for (int j = 0; j < 8; j++) {
                    g[i][j] = gbrut[i][j] - s[i] * s[j] / w;
                }
            }
            return g;
        }

        /** Dominance par eigen symetrique du gramien centre. */
        public SvdRegime dominance(double qMin) {
            double[][] g = gram();
            if (g == null) {
                return null;
            }
            for (int i = 0; i < 8; i++) {
                for (int j = 0; j < 8; j++) {
                    if (!Double.isFinite(g[i][j])) {
                        return null;
                    }
                }
            }
            LinAlg.Eig eig = LinAlg.jacobiEigen(g, 1e-18, 100);
            double[] sigma = new double[8];
            for (int k = 0; k < 8; k++) {
                sigma[k] = Math.sqrt(Math.max(eig.values[k], 0.0));
            }
            double[] u1 = new double[8];
            for (int i = 0; i < 8; i++) {
                u1[i] = eig.vectors[i][0];
            }
            return build(sigma, u1, s.clone(), qMin);
        }
    }
}
