package opengraph.core;

/**
 * Univers Annexe J.2 — 8 devises, 28 paires FX.
 * Port de engine/src/universe.rs — les indices de devises suivent EXACTEMENT
 * l'ordre de CURR du POC (non alphabetique — ne pas reordonner, les tables
 * en dependent).
 */
public final class Universe {

    /** Devises (noeuds du graphe). Indice = CI[ccy] du moteur de reference. */
    public static final String[] CURR = {
        "USD", "EUR", "GBP", "JPY", "CHF", "CAD", "AUD", "NZD"
    };

    /** Les 28 paires FX (cotation marche base/quote), ordre Annexe J. */
    public static final String[] FX = {
        "EURUSD", "GBPUSD", "USDJPY", "USDCHF", "AUDUSD", "USDCAD", "NZDUSD",
        "EURGBP", "EURJPY", "EURCHF", "EURAUD", "EURCAD", "EURNZD", "GBPJPY",
        "GBPCHF", "GBPAUD", "GBPCAD", "GBPNZD", "AUDJPY", "AUDCHF", "AUDCAD",
        "AUDNZD", "CADJPY", "CADCHF", "CHFJPY", "NZDJPY", "NZDCAD", "NZDCHF",
    };

    private Universe() {}

    /** Indice de devise (IllegalArgumentException si inconnue — univers ferme). */
    public static int ci(String ccy) {
        for (int i = 0; i < CURR.length; i++) {
            if (CURR[i].equals(ccy)) {
                return i;
            }
        }
        throw new IllegalArgumentException("devise hors univers: " + ccy);
    }

    /** {base_idx, quote_idx} d'une paire FX par indice de table FX. */
    public static int[] pairNodes(int edge) {
        String s = FX[edge];
        return new int[] {ci(s.substring(0, 3)), ci(s.substring(3, 6))};
    }

    /**
     * Indice d'arete FX de la paire USD d'une devise (cUSD ou USDc) ;
     * -1 pour USD elle-meme (pas de paire USDUSD).
     */
    public static int usdEdge(int c) {
        if (c == 0) {
            return -1;
        }
        for (int e = 0; e < FX.length; e++) {
            int[] ab = pairNodes(e);
            if ((ab[0] == 0 && ab[1] == c) || (ab[0] == c && ab[1] == 0)) {
                return e;
            }
        }
        return -1;
    }
}
