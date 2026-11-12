package opengraph.dynamics;

/**
 * Desequilibre du carnet (Book Imbalance Ratio, §10) :
 *   BIR = (V_bid - V_ask) / (V_bid + V_ask) * exp(-gamma * spread/mid)
 * gamma = 10 — la profondeur vaut moins quand le spread est large.
 * NaN en volume -> 0 (venue sans profondeur fiable : facteur omis par
 * p_persist).
 */
public final class BIR {

    public static final double GAMMA = 10.0;

    private BIR() {}

    public static double compute(double vBid, double vAsk, double spread,
                                 double mid) {
        double sum = vBid + vAsk;
        if (!(sum > 0.0) || !Double.isFinite(sum) || !(mid > 0.0)) {
            return 0.0;
        }
        return (vBid - vAsk) / sum * Math.exp(-GAMMA * spread / mid);
    }
}
