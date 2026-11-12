package opengraph.core;

/**
 * Economie d'execution — port de fees.rs.
 */
public final class Fees {

    /** Notionnel par transaction, en USD (top-of-book POC). */
    public static final double NOTIONAL_USD = 10_000.0;

    /**
     * Commission par cote et par 100 K$ — calee sur les fills reels demo
     * IC Markets cTrader (mediane 3,63, max 4,00, n=37) ; le nominal
     * negocie etait 3,00. ⚠ Venue de calibration != cible : la structure
     * de commissions Dukascopy differe — a recalibrer sur fills demo
     * Dukascopy pendant le run de demo (audit F9).
     */
    public static final double COMM_PER_100K_USD = 4.00;

    /**
     * Repli du cout d'abort par jambe remplie — utilise uniquement quand la
     * mediane de spread de l'arete est absente/non finie.
     */
    public static final double ABORT_COST_PER_LEG_USD = 12.0;

    private Fees() {}

    /** Commission par cote et par jambe au notionnel courant (10 K -> 0,40 $). */
    public static double commUsd() {
        return COMM_PER_100K_USD * NOTIONAL_USD / 100_000.0;
    }
}
