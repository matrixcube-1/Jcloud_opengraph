package opengraph.core;

/** Resultat d'evaluation : meilleur net, indice de chemin, DDD brut. */
public final class EvalResult {
    /** usd_out - NOTIONAL - comm du meilleur chemin. */
    public final double netUsd;
    public final int pathIdx;
    /** usd_out - NOTIONAL — marge brute avant commissions. */
    public final double dddUsd;

    public EvalResult(double netUsd, int pathIdx, double dddUsd) {
        this.netUsd = netUsd;
        this.pathIdx = pathIdx;
        this.dddUsd = dddUsd;
    }
}
