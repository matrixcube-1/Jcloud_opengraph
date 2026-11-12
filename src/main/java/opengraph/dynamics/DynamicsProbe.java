package opengraph.dynamics;

/**
 * Sonde dynamique temps reel (§10 live) : maintient les log-returns du
 * mid d'une serie pour estimer Hurst en ligne, detecte les chocs
 * |r|>2*sigma_roulant et maintient l'excitation Hawkes B incrementale.
 * Legere : un push O(1), hurst() sur la fenetre a la demande.
 */
public final class DynamicsProbe {

    private final int cap;
    private final double[] mid;
    private final long[] ts;
    private int n;
    private double sigmaMean, sigmaM2;
    private int seen;
    private double excitation;
    private long lastShock = Long.MIN_VALUE;
    private final CoxSurvival cox;

    public DynamicsProbe(int cap, CoxSurvival cox) {
        this.cap = cap;
        this.cox = cox;
        this.mid = new double[cap];
        this.ts = new long[cap];
    }

    /** Nouveau mid a t. Met a jour sigma roulant, chocs et excitation B. */
    public void push(long t, double m) {
        if (n > 0) {
            double prev = mid[(n - 1) % cap];
            if (prev > 0.0 && m > 0.0) {
                double r = Math.log(m / prev);
                seen++;
                double d = r - sigmaMean;
                sigmaMean += d / Math.min(seen, 50);
                sigmaM2 += d * (r - sigmaMean);
                double sigma = seen > 10
                        ? Math.sqrt(sigmaM2 / Math.min(seen, 50)) : 0.0;
                if (sigma > 0.0 && Math.abs(r) > 2.0 * sigma) {
                    long dt = lastShock == Long.MIN_VALUE
                            ? 0 : t - lastShock;
                    excitation = lastShock == Long.MIN_VALUE
                            ? cox.alpha
                            : cox.excite(excitation, dt);
                    lastShock = t;
                } else if (lastShock != Long.MIN_VALUE) {
                    excitation = cox.decay(excitation, 1.0);
                }
            }
        }
        mid[n % cap] = m;
        ts[n % cap] = t;
        n++;
    }

    /** H estime sur les log-returns de la fenetre (NaN si insuffisant). */
    public double hurst() {
        int k = Math.min(n, cap);
        if (k < 64) {
            return Double.NaN;
        }
        double[] r = new double[k - 1];
        for (int i = 1; i < k; i++) {
            double a = mid[(n - k + i - 1) % cap];
            double b = mid[(n - k + i) % cap];
            r[i - 1] = a > 0.0 && b > 0.0 ? Math.log(b / a) : 0.0;
        }
        return Hurst.estimate(r);
    }

    public double excitation() {
        return excitation;
    }

    /** Sigma roulant courant des log-returns (0 tant que seen<=10). */
    public double sigma() {
        return seen > 10 ? Math.sqrt(sigmaM2 / Math.min(seen, 50)) : 0.0;
    }
}
