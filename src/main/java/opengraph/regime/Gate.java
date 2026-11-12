package opengraph.regime;

/**
 * Porte dure a hysteresis + modulation g — port de gate.rs (§4-5).
 *
 * Fermeture immediate si T~ > theta_T ou D > theta_D ; reouverture si
 * calme strict (bande delta) et durable (tau_hold). Indisponible => ferme
 * (fail-closed). Etat initial : ferme.
 */
public final class Gate {

    /** Parametres calibres (domaines imposes). */
    public static class GateParams {
        /** theta_T : seuil de tension (unites de z). */
        public double thetaT;
        /** delta_T : bande de reouverture sous theta_T. */
        public double hystT;
        /** theta_D dans [1/7, 1]. */
        public double thetaD;
        /** delta_D : bande de reouverture sous theta_D. */
        public double hystD;
        /** tau_hold : duree de calme exigee avant reouverture (ns). */
        public long holdNs;

        public GateParams(double thetaT, double hystT, double thetaD,
                          double hystD, long holdNs) {
            this.thetaT = thetaT;
            this.hystT = hystT;
            this.thetaD = thetaD;
            this.hystD = hystD;
            this.holdNs = holdNs;
        }
    }

    public final GateParams p;
    private boolean open;
    private long calmSince = -1; // -1 = pas de calme en cours

    public Gate(GateParams p) {
        this.p = p;
        this.open = false;
    }

    public boolean isOpen() {
        return open;
    }

    /**
     * Met a jour l'automate a l'instant tNs.
     * tTilde/dom = NaN => ferme (fail-closed).
     */
    public boolean update(long tNs, double tTilde, double dom) {
        if (!Double.isFinite(tTilde) || !Double.isFinite(dom)) {
            open = false;
            calmSince = -1;
            return false;
        }
        if (open) {
            if (tTilde > p.thetaT || dom > p.thetaD) {
                open = false;
                calmSince = -1;
            }
        } else if (tTilde <= p.thetaT - p.hystT
                && dom <= p.thetaD - p.hystD) {
            if (calmSince < 0) {
                calmSince = tNs;
            }
            if (Math.max(0L, tNs - calmSince) >= p.holdNs) {
                open = true;
            }
        } else {
            calmSince = -1;
        }
        return open;
    }

    /** Parametres de modulation g (Eq. 12). */
    public static class ModParams {
        public double t0, aT, d0, aD, gMin;

        public ModParams(double t0, double aT, double d0, double aD,
                         double gMin) {
            this.t0 = t0;
            this.aT = aT;
            this.d0 = d0;
            this.aD = aD;
            this.gMin = gMin;
        }
    }

    /**
     * Modulation g (Eq. 12) — monotone non croissante, = 1 en regime calme,
     * bornee par g_min.
     */
    public static double modulation(double tTilde, double dom, ModParams p) {
        double e = -p.aT * Math.max(tTilde - p.t0, 0.0)
                - p.aD * Math.max(dom - p.d0, 0.0);
        return Math.min(1.0, Math.max(p.gMin, Math.exp(e)));
    }
}
