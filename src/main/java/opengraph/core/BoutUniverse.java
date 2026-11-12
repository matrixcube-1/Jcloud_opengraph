package opengraph.core;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Univers operationnel J.3 — 26 bouts (13 indices + 11 metaux + 2 energie).
 * Les 6 softs sont definitivement exclus (decision 31/10/2026, AGENTS.md).
 * Bouts par devise cotee : USD 10, EUR 7, GBP 2, JPY 2, CHF 2, AUD 3.
 */
public final class BoutUniverse {

    public static final Bout[] BOUTS = {
        // ---- USD (10) ----
        new Bout("USA30IDXUSD", 0, Bout.Cls.INDEX,  Bout.Tier.L, 0.05, 0.0, 1.0, 1.0, jfx("USA30IDXUSD")),
        new Bout("USA500IDXUSD", 0, Bout.Cls.INDEX,  Bout.Tier.S, 0.05, 0.0, 1.0, 1.0, jfx("USA500IDXUSD")),
        new Bout("USSC2000IDXUSD", 0, Bout.Cls.INDEX,  Bout.Tier.L, 0.05, 0.0, 1.0, 1.0, jfx("USSC2000IDXUSD")),
        new Bout("USATECHIDXUSD", 0, Bout.Cls.INDEX,  Bout.Tier.S, 0.05, 0.0, 1.0, 1.0, jfx("USATECHIDXUSD")),
        new Bout("XAUUSD", 0, Bout.Cls.METAL,  Bout.Tier.S, 0.05, 3.50, 100.0, 0.01, jfx("XAUUSD")),
        new Bout("XAGUSD", 0, Bout.Cls.METAL,  Bout.Tier.L, 0.05, 3.50, 5000.0, 0.01, jfx("XAGUSD")),
        new Bout("LIGHTCMDUSD", 0, Bout.Cls.ENERGY, Bout.Tier.L, 0.10, 0.0, 1000.0, 1.0, jfx("LIGHTCMDUSD")),
        new Bout("BRENTCMDUSD", 0, Bout.Cls.ENERGY, Bout.Tier.L, 0.10, 0.0, 1000.0, 1.0, jfx("BRENTCMDUSD")),
        new Bout("XPTCMDUSD", 0, Bout.Cls.METAL,  Bout.Tier.X, 0.05, 3.50, 50.0, 0.1, jfx("XPTCMDUSD")),
        new Bout("XPDCMDUSD", 0, Bout.Cls.METAL,  Bout.Tier.X, 0.05, 3.50, 100.0, 0.1, jfx("XPDCMDUSD")),
        // ---- EUR (7) ----
        new Bout("DEUIDXEUR", 1, Bout.Cls.INDEX,  Bout.Tier.S, 0.05, 0.0, 1.0, 1.0, jfx("DEUIDXEUR")),
        new Bout("ITAIDXEUR", 1, Bout.Cls.INDEX,  Bout.Tier.L, 0.05, 0.0, 1.0, 1.0, jfx("ITAIDXEUR")),
        new Bout("FRAIDXEUR", 1, Bout.Cls.INDEX,  Bout.Tier.L, 0.05, 0.0, 1.0, 1.0, jfx("FRAIDXEUR")),
        new Bout("EUSIDXEUR", 1, Bout.Cls.INDEX,  Bout.Tier.L, 0.05, 0.0, 1.0, 1.0, jfx("EUSIDXEUR")),
        new Bout("NLDIDXEUR", 1, Bout.Cls.INDEX,  Bout.Tier.L, 0.05, 0.0, 1.0, 1.0, jfx("NLDIDXEUR")),
        new Bout("XAUEUR", 1, Bout.Cls.METAL,  Bout.Tier.L, 0.05, 3.50, 100.0, 0.01, jfx("XAUEUR")),
        new Bout("XAGEUR", 1, Bout.Cls.METAL,  Bout.Tier.X, 0.05, 3.50, 5000.0, 0.01, jfx("XAGEUR")),
        // ---- GBP (2) ----
        new Bout("GBRIDXGBP", 2, Bout.Cls.INDEX,  Bout.Tier.L, 0.05, 0.0, 1.0, 1.0, jfx("GBRIDXGBP")),
        new Bout("XAUGBP", 2, Bout.Cls.METAL,  Bout.Tier.X, 0.05, 3.50, 100.0, 0.01, jfx("XAUGBP")),
        // ---- JPY (2) ----
        new Bout("JPNIDXJPY", 3, Bout.Cls.INDEX,  Bout.Tier.S, 0.05, 0.0, 1.0, 1.0, jfx("JPNIDXJPY")),
        new Bout("XAUJPY", 3, Bout.Cls.METAL,  Bout.Tier.X, 0.05, 3.50, 100.0, 0.01, jfx("XAUJPY")),
        // ---- CHF (2) — SWI20 cote CHF, conversion USD via USDCHF (J.3/A10) ----
        new Bout("CHEIDXCHF", 4, Bout.Cls.INDEX,  Bout.Tier.L, 0.05, 0.0, 1.0, 1.0, jfx("CHEIDXCHF")),
        new Bout("XAUCHF", 4, Bout.Cls.METAL,  Bout.Tier.X, 0.05, 3.50, 100.0, 0.01, jfx("XAUCHF")),
        // ---- AUD (3) ----
        new Bout("AUSIDXAUD", 6, Bout.Cls.INDEX,  Bout.Tier.L, 0.05, 0.0, 1.0, 1.0, jfx("AUSIDXAUD")),
        new Bout("XAUAUD", 6, Bout.Cls.METAL,  Bout.Tier.X, 0.05, 3.50, 100.0, 0.01, jfx("XAUAUD")),
        new Bout("XAGAUD", 6, Bout.Cls.METAL,  Bout.Tier.X, 0.05, 3.50, 5000.0, 0.01, jfx("XAGAUD")),
    };

    /**
     * Croises metal sans instrument JForex (absents de l'enum 2.13.99 et de
     * la gamme Dukascopy ; dukascopy-node : barres seules, pas de ticks ->
     * prix synthetiques). Evalues pour la parite, jamais souscrits/executes.
     */
    private static final java.util.Set<String> NON_TRADABLE = new java.util.HashSet<>(
            java.util.Arrays.asList("XAUEUR", "XAGEUR", "XAUGBP", "XAUJPY",
                    "XAUCHF", "XAUAUD", "XAGAUD"));

    private static final Map<String, Integer> IDX = new HashMap<>();
    private static final List<int[]> BY_CCY = new ArrayList<>();

    static {
        for (int i = 0; i < BOUTS.length; i++) {
            IDX.put(BOUTS[i].name, i);
        }
        for (int c = 0; c < 8; c++) {
            List<Integer> l = new ArrayList<>();
            for (int i = 0; i < BOUTS.length; i++) {
                if (BOUTS[i].qc == c) {
                    l.add(i);
                }
            }
            int[] a = new int[l.size()];
            for (int i = 0; i < a.length; i++) {
                a[i] = l.get(i);
            }
            BY_CCY.add(a);
        }
    }

    private BoutUniverse() {}

    /**
     * Nom JForex du bout : indices "*.IDX/CCY", energie "*.CMD/USD",
     * metaux "XA?/CCY". Noms Dukascopy usuels — a valider en demo avant
     * tout envoi live (documente dans DEVIATIONS.md).
     */
    static String jfx(String name) {
        if (name.startsWith("XPT") || name.startsWith("XPD")) {
            return name.substring(0, 3) + ".CMD/" + name.substring(6);
        }
        if (name.startsWith("LIGHT") || name.startsWith("BRENT")) {
            return name.substring(0, 5) + ".CMD/" + name.substring(8);
        }
        if (name.startsWith("XA")) {
            return name.substring(0, 3) + "/" + name.substring(3);
        }
        // indices : AAAIDXCCC -> AAA.IDX/CCC
        int i = name.indexOf("IDX");
        if (i > 0) {
            return name.substring(0, i) + ".IDX/" + name.substring(i + 3);
        }
        return name;
    }

    public static int indexOf(String name) {
        Integer i = IDX.get(name);
        if (i == null) {
            throw new IllegalArgumentException("bout hors univers: " + name);
        }
        return i;
    }

    /** Le bout b a-t-il un instrument JForex negociable ? */
    public static boolean jfxTradable(int b) {
        return !NON_TRADABLE.contains(BOUTS[b].name);
    }

    /** Indices des bouts cotes dans la devise c. */
    public static int[] quotedIn(int c) {
        return BY_CCY.get(c);
    }

    /** Tous les symboles bouts, dans l'ordre de la table. */
    public static String[] symbols() {
        String[] s = new String[BOUTS.length];
        for (int i = 0; i < s.length; i++) {
            s[i] = BOUTS[i].name;
        }
        return s;
    }
}
