package opengraph.dynamics;

import java.util.ArrayList;
import java.util.List;

/**
 * Detection des chocs (§10) : |retour mid| > k * sigma_roulant.
 * sigma = ecart-type des `window` rendements precedents ; k = 2 par defaut.
 * Entree du fit Hawkes — un choc = un evenement de l'intensite.
 */
public final class Shocks {

    private Shocks() {}

    public static long[] detect(long[] ts, double[] mid, int window,
                                double k) {
        List<Long> out = new ArrayList<>();
        int n = ts.length;
        for (int i = 1; i < n; i++) {
            double r = Math.log(mid[i] / mid[i - 1]);
            int lo = Math.max(1, i - window);
            int cnt = i - lo;
            if (cnt < 10) {
                continue; // sigma non estimable
            }
            double mean = 0.0;
            for (int j = lo; j < i; j++) {
                mean += Math.log(mid[j] / mid[j - 1]);
            }
            mean /= cnt;
            double var = 0.0;
            for (int j = lo; j < i; j++) {
                double d = Math.log(mid[j] / mid[j - 1]) - mean;
                var += d * d;
            }
            double sigma = Math.sqrt(var / cnt);
            if (sigma > 0.0 && Math.abs(r) > k * sigma) {
                out.add(ts[i]);
            }
        }
        long[] a = new long[out.size()];
        for (int i = 0; i < a.length; i++) {
            a[i] = out.get(i);
        }
        return a;
    }
}
