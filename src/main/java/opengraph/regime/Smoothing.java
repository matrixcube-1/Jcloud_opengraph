package opengraph.regime;

import java.util.ArrayDeque;
import java.util.Arrays;
import java.util.Deque;

/**
 * Lissage temporel + standardisation robuste — port de smoothing.rs
 * (conception §2.5).
 *
 * EWMA a demi-vie h sur observations a instants irreguliers :
 *   T_k = T_(k-1) + a_k (x_k - T_(k-1)),  a_k = 1 - exp(-ln2 * dt/h).
 * Z robuste : T~ = (x - med(H)) / (1.4826 * max(MAD(H), m_min)),
 * H = W_T dernieres valeurs LISSAEES strictement anterieures.
 */
public final class Smoothing {

    private Smoothing() {}

    /** EWMA a demi-vie sur temps evenementiel irregulier. */
    public static class Ewma {
        public final double halfLife;
        private long lastT = -1;
        private double value;
        private boolean started;

        public Ewma(double halfLife) {
            this.halfLife = halfLife;
        }

        /** Premiere observation : valeur initialisee a l'observation.
         * Ensuite : a = 1 - 2^(-dt/h). */
        public double update(long t, double x) {
            if (!started) {
                value = x;
                lastT = t;
                started = true;
                return x;
            }
            double dt = Math.max(0L, t - lastT);
            double a = 1.0 - Math.exp(-Math.log(2.0) * dt / halfLife);
            value += a * (x - value);
            lastT = t;
            return value;
        }

        /** Valeur courante, Double.NaN avant la premiere observation. */
        public double value() {
            return started ? value : Double.NaN;
        }

        public boolean isStarted() {
            return started;
        }
    }

    /** Z robuste sur historique glissant de taille W_T.
     * Tant que |H| < wMin -> indisponible (NaN, fail-closed). */
    public static class RobustZ {
        public final int window;
        public final int wMin;
        /** Plancher de denominateur (garde, pas un seuil de decision). */
        public final double mMin;
        private final Deque<Double> hist = new ArrayDeque<>();

        public RobustZ(int window, int wMin, double mMin) {
            this.window = window;
            this.wMin = wMin;
            this.mMin = mMin;
        }

        /**
         * Standardise x CONTRE l'historique actuel, PUIS pousse x dans H.
         * Retourne NaN tant que |H| < wMin (aucune fuite).
         */
        public double update(double x) {
            double out;
            if (hist.size() >= wMin) {
                double[] h = new double[hist.size()];
                int k = 0;
                for (double v : hist) {
                    h[k++] = v;
                }
                double med = median(h);
                double[] dev = new double[h.length];
                for (int i = 0; i < h.length; i++) {
                    dev[i] = Math.abs(h[i] - med);
                }
                double mad = median(dev);
                double denom = 1.4826 * Math.max(mad, mMin);
                out = (x - med) / denom;
            } else {
                out = Double.NaN;
            }
            hist.addLast(x);
            while (hist.size() > window) {
                hist.pollFirst();
            }
            return out;
        }

        public int len() {
            return hist.size();
        }
    }

    /** Mediane d'un echantillon (copie triee — boucle lente). */
    public static double median(double[] v) {
        int n = v.length;
        if (n == 0) {
            return Double.NaN;
        }
        Arrays.sort(v);
        if (n % 2 == 1) {
            return v[n / 2];
        }
        return 0.5 * (v[n / 2 - 1] + v[n / 2]);
    }
}
