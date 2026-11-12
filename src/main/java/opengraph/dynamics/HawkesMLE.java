package opengraph.dynamics;

/**
 * <b>DORMANT</b> — utilise en tests uniquement ; le pipeline ne
 * l'appelle jamais (fit externe requis pour activer E2).
 * Estimateur MLE de Hawkes a noyau exponentiel (document scelle §10) :
 *   lambda(t) = mu + sum_{t_i<t} alpha * exp(-beta*(t - t_i))
 * Log-vraisemblance sur [0,T] :
 *   l = sum_i ln(mu + alpha*A_i) - mu*T
 *       - (alpha/beta) * sum_i (1 - exp(-beta*(T - t_i)))
 *   A_i = exp(-beta*dt_i) * (1 + A_{i-1})   (recursion O(n))
 * Ratio de branchement n = alpha/beta ; stationnarite n < 1.
 * Fit par Nelder-Mead sur (ln mu, ln alpha, ln beta) — deterministe.
 */
public final class HawkesMLE {

    public static final class Fit {
        public final double mu, alpha, beta, logLik;
        Fit(double mu, double alpha, double beta, double logLik) {
            this.mu = mu;
            this.alpha = alpha;
            this.beta = beta;
            this.logLik = logLik;
        }
        /** Ratio de branchement (stationnarite < 1). */
        public double branching() {
            return beta > 0.0 ? alpha / beta : Double.POSITIVE_INFINITY;
        }
    }

    private HawkesMLE() {}

    /** ts = instants des chocs (ms, croissant) ; T = horizon (ms). */
    public static Fit fit(double[] ts, double T) {
        int n = ts.length;
        if (n < 5 || T <= ts[n - 1]) {
            double r = T > 0 ? n / T : 0.0;
            return new Fit(Math.max(r, 1e-12), 0.0, 1.0,
                    n * Math.log(Math.max(r, 1e-12)) - r * T);
        }
        double rate = n / T;
        double meanGap = (ts[n - 1] - ts[0]) / Math.max(1, n - 1);
        double[] x0 = {Math.log(rate * 0.5), Math.log(rate * 0.5),
                Math.log(1.0 / Math.max(meanGap, 1e-9))};
        double[] x = nelderMead(x0, (double[] p) ->
                -logLik(Math.exp(p[0]), Math.exp(p[1]), Math.exp(p[2]),
                        ts, T));
        return new Fit(Math.exp(x[0]), Math.exp(x[1]), Math.exp(x[2]),
                logLik(Math.exp(x[0]), Math.exp(x[1]), Math.exp(x[2]),
                        ts, T));
    }

    static double logLik(double mu, double alpha, double beta,
                         double[] ts, double T) {
        if (!(mu > 0.0) || !(alpha >= 0.0) || !(beta > 0.0)) {
            return Double.NEGATIVE_INFINITY;
        }
        int n = ts.length;
        double a = 0.0;      // A_i courant
        double sum = 0.0;    // sum ln lambda_i
        double tail = 0.0;   // sum (1 - exp(-beta*(T - t_i)))
        double prev = ts[0] - 1e-9;
        for (int i = 0; i < n; i++) {
            double dt = ts[i] - prev;
            a = Math.exp(-beta * dt) * (i == 0 ? 0.0 : 1.0 + a);
            double lam = mu + alpha * a;
            if (!(lam > 0.0) || !Double.isFinite(lam)) {
                return Double.NEGATIVE_INFINITY;
            }
            sum += Math.log(lam);
            tail += 1.0 - Math.exp(-beta * (T - ts[i]));
            prev = ts[i];
        }
        return sum - mu * T - (alpha / beta) * tail;
    }

    /** Nelder-Mead (minimisation), simplex standard, 400 iterations max. */
    private static double[] nelderMead(double[] x0, Obj f) {
        int d = x0.length;
        double[][] s = new double[d + 1][d];
        double[] fv = new double[d + 1];
        for (int i = 0; i <= d; i++) {
            System.arraycopy(x0, 0, s[i], 0, d);
            if (i > 0) {
                s[i][i - 1] += 0.3;
            }
            fv[i] = f.apply(s[i]);
            if (!Double.isFinite(fv[i])) {
                fv[i] = 1e300;
            }
        }
        for (int it = 0; it < 400; it++) {
            // tri par valeur
            for (int i = 0; i < d; i++) {
                for (int j = i + 1; j <= d; j++) {
                    if (fv[j] < fv[i]) {
                        double tf = fv[i];
                        fv[i] = fv[j];
                        fv[j] = tf;
                        double[] tx = s[i];
                        s[i] = s[j];
                        s[j] = tx;
                    }
                }
            }
            if (Math.abs(fv[d] - fv[0]) < 1e-10 * (1 + Math.abs(fv[0]))) {
                break;
            }
            double[] c = new double[d];
            for (int i = 0; i < d; i++) {
                for (int j = 0; j < d; j++) {
                    c[j] += s[i][j];
                }
            }
            for (int j = 0; j < d; j++) {
                c[j] /= d;
            }
            double[] r = new double[d];
            for (int j = 0; j < d; j++) {
                r[j] = 2.0 * c[j] - s[d][j];
            }
            double fr = ev(f, r);
            if (fr < fv[0]) { // expansion
                double[] e = new double[d];
                for (int j = 0; j < d; j++) {
                    e[j] = 2.0 * r[j] - c[j];
                }
                double fe = ev(f, e);
                System.arraycopy(fe < fr ? e : r, 0, s[d], 0, d);
                fv[d] = Math.min(fe, fr);
            } else if (fr < fv[d - 1]) {
                System.arraycopy(r, 0, s[d], 0, d);
                fv[d] = fr;
            } else { // contraction
                double[] k = new double[d];
                for (int j = 0; j < d; j++) {
                    k[j] = 0.5 * (c[j] + s[d][j]);
                }
                double fk = ev(f, k);
                if (fk < fv[d]) {
                    System.arraycopy(k, 0, s[d], 0, d);
                    fv[d] = fk;
                } else { // shrink
                    for (int i = 1; i <= d; i++) {
                        for (int j = 0; j < d; j++) {
                            s[i][j] = 0.5 * (s[i][j] + s[0][j]);
                        }
                        fv[i] = ev(f, s[i]);
                    }
                }
            }
        }
        // meilleur sommet
        int bi = 0;
        for (int i = 1; i <= d; i++) {
            if (fv[i] < fv[bi]) {
                bi = i;
            }
        }
        return s[bi];
    }

    private static double ev(Obj f, double[] x) {
        double v = f.apply(x);
        return Double.isFinite(v) ? v : 1e300;
    }

    private interface Obj {
        double apply(double[] x);
    }
}
