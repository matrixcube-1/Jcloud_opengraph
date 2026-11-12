package opengraph.regime;

/**
 * Instantane immuable de regime — port de snapshot.rs.
 * Publie par la boucle lente, lu sans verrou par le chemin chaud.
 */
public final class RegimeSnapshot {
    /** Numero de sequence strictement croissant. */
    public final long seq;
    /** Horodatage evenementiel de l'instantane (ns). */
    public final long eventTimeNs;
    /** Etat de la porte a hysteresis. */
    public final boolean gateOpen;
    /** Tension standardisee T~ (z robuste), NaN si indisponible. */
    public final double tTilde;
    /** Dominance spectrale D dans [1/7, 1], NaN si indisponible. */
    public final double dominance;
    /** Devise dominante c* (indice 0..7), -1 si absent. */
    public final int dominant;
    /** Direction du facteur dominant (+1/-1). */
    public final int direction;

    public RegimeSnapshot(long seq, long eventTimeNs, boolean gateOpen,
                          double tTilde, double dominance, int dominant,
                          int direction) {
        this.seq = seq;
        this.eventTimeNs = eventTimeNs;
        this.gateOpen = gateOpen;
        this.tTilde = tTilde;
        this.dominance = dominance;
        this.dominant = dominant;
        this.direction = direction;
    }

    /**
     * Chemin chaud : une lecture, un test de fraicheur, un test de bit.
     * Port de regime_allows_send — snapshot perime ou futur => faux.
     */
    public static boolean allowsSend(RegimeSnapshot s, long nowNs,
                                     long staleNs) {
        return s.gateOpen
                && nowNs >= s.eventTimeNs
                && nowNs - s.eventTimeNs <= staleNs;
    }
}
