package opengraph.core;

/** Parametres d'une sequence d'execution (exec.rs::ExecParams). */
public final class ExecParams {
    /** Latence par jambe (ms, flottant). */
    public final double latMs;
    /** Mouvement de prix tolere entre envoi et fill, en bp. */
    public final double tauSlipBp;
    /** Fraction minimale de fill acceptee (rejet en dessous). */
    public final double qMin;
    /** Garde volume top-of-book — true = spec Rust production. */
    public final boolean volGuard;

    // ---- couche E1-E9 du document (docMode) ----
    /** E3a : age max de la quote au fill (ms). Infini = inactif. */
    public final double staleMs;
    /** E3b : spread max en multiple de la mediane. Infini = inactif. */
    public final double spreadCapMult;
    /** E4 : continue ssi E[net resid] >= -(C_unwind + m_abort). */
    public final double mAbortUsd;
    /** E6 : tentatives de liquidation du residuel au unwind. */
    public final int nU;
    /** true = semantique document E1-E9 (unwind reel + E3/E4). */
    public final boolean docMode;

    public ExecParams(double latMs, double tauSlipBp, double qMin,
                      boolean volGuard) {
        this(latMs, tauSlipBp, qMin, volGuard, Double.POSITIVE_INFINITY,
                Double.POSITIVE_INFINITY, Double.NEGATIVE_INFINITY, 0,
                false);
    }

    public ExecParams(double latMs, double tauSlipBp, double qMin,
                      boolean volGuard, double staleMs,
                      double spreadCapMult, double mAbortUsd, int nU,
                      boolean docMode) {
        this.latMs = latMs;
        this.tauSlipBp = tauSlipBp;
        this.qMin = qMin;
        this.volGuard = volGuard;
        this.staleMs = staleMs;
        this.spreadCapMult = spreadCapMult;
        this.mAbortUsd = mAbortUsd;
        this.nU = nU;
        this.docMode = docMode;
    }

    /** Parametres E1-E9 du document (defauts §17 a calibrer). */
    public static ExecParams doc(double latMs, double tauSlipBp,
                                 double qMin, double freshMs,
                                 double mAbortUsd, int nU) {
        return new ExecParams(latMs, tauSlipBp, qMin, true, freshMs, 1.7,
                mAbortUsd, nU, true);
    }
}
