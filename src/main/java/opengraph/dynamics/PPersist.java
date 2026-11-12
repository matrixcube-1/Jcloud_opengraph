package opengraph.dynamics;

/**
 * Score de persistance (§10) — filtre d'admission avant sizing :
 *   p_persist = S(h) * 1[H > 0.55] * max(0, BIR_ajuste)
 * BIR indisponible (NaN) -> facteur omis (= 1), convention venue sans
 * profondeur fiable. Admission si p >= tau_persist.
 */
public final class PPersist {

    public final double tau;
    public final double hMin;

    public PPersist(double tau) {
        this(tau, 0.55);
    }

    public PPersist(double tau, double hMin) {
        this.tau = tau;
        this.hMin = hMin;
    }

    public double p(double sH, double hurst, double birAdj) {
        if (!(hurst > hMin)) { // fail-closed : NaN -> rejet
            return 0.0;
        }
        double birF = Double.isNaN(birAdj) ? 1.0 : Math.max(0.0, birAdj);
        return sH * birF;
    }

    public boolean admit(double sH, double hurst, double birAdj) {
        return p(sH, hurst, birAdj) >= tau;
    }
}
