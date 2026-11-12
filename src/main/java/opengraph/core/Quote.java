package opengraph.core;

/**
 * Tick de cotation (Dukascopy, resolution ms) — port de quote.rs.
 *
 * {@code vol} est le volume affiche en unites propres a l'instrument
 * (millions de devise de base pour FX d'apres la convention du flux).
 */
public final class Quote {
    /** Horodatage en millisecondes epoch. */
    public final long tsMs;
    public final double bid;
    public final double ask;
    public final double vol;

    public Quote(long tsMs, double bid, double ask, double vol) {
        this.tsMs = tsMs;
        this.bid = bid;
        this.ask = ask;
        this.vol = vol;
    }

    public double mid() {
        return 0.5 * (bid + ask);
    }
}
