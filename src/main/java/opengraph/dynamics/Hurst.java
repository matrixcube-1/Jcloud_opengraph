package opengraph.dynamics;

/**
 * Estimateur de Hurst par plage rescalee (R/S, §10) :
 * pour chaque taille de bloc n, moyenne de R/S ; pente OLS de
 * (ln n, ln R/S) = H. Filtre de persistance : qualite si H > 0.55.
 */
public final class Hurst {

    private static final int[] NS = {8, 16, 32, 64, 128, 256, 512, 1024};

    private Hurst() {}

    /** H estime sur la serie de rendements r (log-returns du mid). */
    public static double estimate(double[] r) {
        int n0 = r.length;
        if (n0 < 64) {
            return Double.NaN;
        }
        int m = 0;
        double[] xs = new double[NS.length];
        double[] ys = new double[NS.length];
        for (int n : NS) {
            if (n > n0) {
                break;
            }
            int k = n0 / n;
            if (k < 1) {
                break;
            }
            double acc = 0.0;
            for (int blk = 0; blk < k; blk++) {
                acc += rescaledRange(r, blk * n, n);
            }
            double rs = acc / k;
            if (!(rs > 0.0) || !Double.isFinite(rs)) {
                continue;
            }
            xs[m] = Math.log(n);
            ys[m] = Math.log(rs);
            m++;
        }
        if (m < 3) {
            return Double.NaN;
        }
        // OLS pente
        double sx = 0, sy = 0, sxx = 0, sxy = 0;
        for (int i = 0; i < m; i++) {
            sx += xs[i];
            sy += ys[i];
            sxx += xs[i] * xs[i];
            sxy += xs[i] * ys[i];
        }
        double den = m * sxx - sx * sx;
        if (Math.abs(den) < 1e-15) {
            return Double.NaN;
        }
        return (m * sxy - sx * sy) / den;
    }

    private static double rescaledRange(double[] r, int off, int n) {
        double mean = 0.0;
        for (int i = 0; i < n; i++) {
            mean += r[off + i];
        }
        mean /= n;
        double sd = 0.0, cum = 0.0, mn = 0.0, mx = 0.0;
        for (int i = 0; i < n; i++) {
            double d = r[off + i] - mean;
            sd += d * d;
            cum += d;
            mn = Math.min(mn, cum);
            mx = Math.max(mx, cum);
        }
        sd = Math.sqrt(sd / n);
        if (sd <= 0.0) {
            return 0.0;
        }
        return (mx - mn) / sd;
    }
}
