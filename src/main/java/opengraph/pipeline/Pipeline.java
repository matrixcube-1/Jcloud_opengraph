package opengraph.pipeline;

import opengraph.core.Engine;
import opengraph.core.EvalResult;
import opengraph.core.ExtPathTables;
import opengraph.core.PathTables;
import opengraph.dynamics.CoxSurvival;
import opengraph.dynamics.PPersist;

/**
 * Pipeline §19 du document — enchainement des etages d'admission avant
 * envoi d'une sequence :
 *   eval (net ex) -> E2 survie S(T_seq) > 0.30 -> p_persist >= tau ->
 *   fenetre rollover -> sizing (delegue a l'appelant).
 * Partagee par la strategie JForex, le replay et la campagne.
 */
public final class Pipeline {

    public enum Decision {
        FIRE, NO_EDGE, E2_SURVIVAL, P_PERSIST, ROLLOVER
    }

    public static final class Out {
        public final Decision decision;
        public final EvalResult eval;   // net evalue (null si NO_EDGE)
        public final boolean bout;      // true si le gagnant est un bout
        public final double sH;         // S(T_seq) calcule
        public final double pPersist;

        Out(Decision d, EvalResult e, boolean b, double s, double p) {
            decision = d;
            eval = e;
            bout = b;
            sH = s;
            pPersist = p;
        }
    }

    private final Engine eng;
    private final PathTables pt;
    private final ExtPathTables xt;
    private final CoxSurvival cox;
    private final PPersist persist;
    private final double dddMin;

    /** T_seq : horizon de sequence estime (ms) — lat_ms x nb ordres x m_seq. */
    public double mSeq = 1.5;
    /** Hurst courant de l'arrivee (mis a jour par l'appelant). */
    public double hurst = 0.5;
    /** Excitation Hawkes courante B (mise a jour par l'appelant). */
    public double excitationB = 0.0;
    /**
     * Rafraichit hurst/excitationB a partir du chemin gagnant — appele par
     * decide() juste avant les portes E2/p_persist (le gagnant n'est connu
     * qu'apres l'eval interne). Args : (gagnant, estBout). Null = valeurs
     * pre-reglees par l'appelant.
     */
    public java.util.function.BiConsumer<EvalResult, Boolean> onBest;
    /** BIR ajuste courant (NaN = facteur omis). */
    public double birAdj = Double.NaN;
    public double latMs = 2.5;

    public Pipeline(Engine eng, PathTables pt, ExtPathTables xt,
                    CoxSurvival cox, double tauPersist, double dddMin) {
        this.eng = eng;
        this.pt = pt;
        this.xt = xt;
        this.cox = cox;
        this.persist = new PPersist(tauPersist);
        this.dddMin = dddMin;
    }

    /** Fenetre rollover : 23:55 -> 00:10 (ms depuis minuit UTC). */
    public static boolean rollover(long epochMs) {
        long msod = Math.floorMod(epochMs, 86_400_000L);
        return msod >= 23 * 3_600_000L + 55 * 60_000L
                || msod < 10 * 60_000L;
    }

    public Out decide(long t, double freshMs, double spreadCap,
                      double commScale) {
        if (rollover(t)) {
            return new Out(Decision.ROLLOVER, null, false, 0, 0);
        }
        EvalResult fx = eng.evalAt(t, freshMs, spreadCap, commScale, pt);
        EvalResult bx = eng.evalBoutAt(t, freshMs, spreadCap, commScale,
                pt, xt);
        EvalResult best = fx;
        boolean isBout = false;
        if (bx != null && (best == null || bx.netUsd > best.netUsd)) {
            best = bx;
            isBout = true;
        }
        if (best == null || best.netUsd < dddMin) {
            return new Out(Decision.NO_EDGE, best, isBout, 0, 0);
        }
        if (onBest != null) {
            onBest.accept(best, isBout);
        }
        // E2 : S(T_seq) > 0.30 — ordres = jambes + [entree] + [sortie]
        // (+1 pour la jambe bout)
        int legs = isBout ? pt.pL[xt.fxPi[best.pathIdx]] + 1
                : pt.pL[best.pathIdx];
        int nOrd = legs + 2;
        double tSeq = nOrd * latMs * mSeq;
        double sH = cox.s(tSeq, excitationB);
        if (sH <= 0.30) {
            return new Out(Decision.E2_SURVIVAL, best, isBout, sH, 0);
        }
        double p = persist.p(sH, hurst, birAdj);
        if (p < persist.tau) {
            return new Out(Decision.P_PERSIST, best, isBout, sH, p);
        }
        return new Out(Decision.FIRE, best, isBout, sH, p);
    }
}
