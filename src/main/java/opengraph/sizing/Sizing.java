package opengraph.sizing;

/**
 * Sizing LP du document (§10, etape HiGHS du pipeline §19) :
 *   max sum_j d_j * S(h)_j * x_j
 * sous :
 *   - borne Kelly fractionnee : x_j * notional_j <= kellyCap_j
 *     (x_max = kappa * W0 * N_ref * mu/sigma^2, fourni par le calibrage)
 *   - marge : sum_j marginRate_j * notional_j * x_j <= equite / rho_min
 *   - concentration : x_j * notional_j <= concCap * equite
 *     (borne par nom, substitut lineaire du Herfindahl du document)
 * puis arrondi vers le bas au pas de granularite + re-check regle 21.
 * ES_RU : non cable tant qu'aucune matrice de scenarios n'est fournie
 * (journal de deviation documentaire).
 */
public final class Sizing {

    public static final class Cand {
        /** Edge attendu par unite de notional, en USD. */
        public double dUsd;
        /** Survie S(h) du candidat. */
        public double survival;
        /** Taux de marge de sa classe (0.05 = 1:20). */
        public double marginRate;
        /** Borne Kelly en USD de notional. */
        public double kellyCapUsd;
        /** Notional d'une unite x (10_000). */
        public double notionalUsd = 10_000.0;
        /** Pas d'arrondi en USD de notional. */
        public double stepUsd = 1.0;
    }

    public final double equityUsd;
    public final double rhoMin;
    public final double concCap;

    /** rhoMin : Niveau = Equite/Marge minimal (300 % = 3.0). */
    public Sizing(double equityUsd, double rhoMin, double concCap) {
        this.equityUsd = equityUsd;
        this.rhoMin = rhoMin;
        this.concCap = concCap;
    }

    /**
     * Alloue x[] (unites de notional par candidat), ou null si rien
     * d'allouable. Variables d'objectif d*S par unite.
     */
    public double[] allocate(Cand[] cands) {
        int n = cands.length;
        int[] act = new int[n];
        int na = 0;
        for (int j = 0; j < n; j++) {
            if (cands[j].dUsd * cands[j].survival > 0.0
                    && cands[j].kellyCapUsd > 0.0) {
                act[na++] = j;
            }
        }
        if (na == 0) {
            return null;
        }
        // LP sur les candidats actifs
        double[] c = new double[na];
        double[] u = new double[na];
        for (int k = 0; k < na; k++) {
            Cand cd = cands[act[k]];
            c[k] = cd.dUsd * cd.survival;
            u[k] = Math.min(cd.kellyCapUsd, concCap * equityUsd)
                    / cd.notionalUsd;
        }
        // lignes : marge (1) + bornes (na)
        double[][] A = new double[1 + na][na];
        double[] b = new double[1 + na];
        for (int k = 0; k < na; k++) {
            A[0][k] = cands[act[k]].marginRate * cands[act[k]].notionalUsd;
        }
        b[0] = equityUsd / rhoMin;
        for (int k = 0; k < na; k++) {
            A[1 + k][k] = 1.0;
            b[1 + k] = u[k];
        }
        Simplex.Result r = Simplex.maximize(c, A, b);
        if (!r.optimal) {
            return null;
        }
        double[] x = new double[n];
        boolean any = false;
        double marginBudget = equityUsd / rhoMin;
        double used = 0.0;
        // arrondi vers le bas + re-check marge (regle d'envoi 21)
        for (int k = 0; k < na; k++) {
            Cand cd = cands[act[k]];
            double usd = r.x[k] * cd.notionalUsd;
            usd = Math.floor(usd / cd.stepUsd) * cd.stepUsd;
            double need = cd.marginRate * usd;
            if (usd > 0.0 && used + need <= marginBudget + 1e-9) {
                x[act[k]] = usd / cd.notionalUsd;
                used += need;
                any = true;
            }
        }
        return any ? x : null;
    }
}
