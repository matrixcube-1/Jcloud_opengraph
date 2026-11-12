import opengraph.core.*;

/**
 * Test d'injection : fabrique un desequilibre de cross-rate connu et
 * verifie que evalAt le detecte (net > 0, argmax = chemin EUR->JPY).
 * Cas controle : marche coherent -> net < 0 attendu.
 */
public final class InjectTest {

    static SymbolSeries sym(String s, double bid, double ask, long t) {
        return new SymbolSeries(s,
                new long[] {t},
                new double[] {bid},
                new double[] {ask},
                new double[] {1e6},
                new double[] {1e6});
    }

    static void decode(PathTables pt, int pi) {
        StringBuilder sb = new StringBuilder();
        sb.append("s0=").append(Universe.CURR[pt.pS[pi]]);
        for (int li = 0; li < pt.pL[pi]; li++) {
            sb.append(" ->").append(Universe.FX[pt.pE[pi][li]])
              .append(pt.pDir[pi][li] == 1 ? "+" : "-");
        }
        sb.append(" end=").append(Universe.CURR[pt.pEnd[pi]]);
        System.out.println("  argmax: " + sb);
    }

    public static void main(String[] a) throws Exception {
        long t = 1000L;
        PathTables pt = PathTables.build();

        // ---- CAS 1 : marche COHERENT (juste prix) ----
        // EURUSD 1.1009/1.1010, USDJPY 150.09/150.10 -> EURJPY juste ~165.26
        Engine e1 = new Engine(java.util.List.of(
                sym("EURUSD", 1.1009, 1.1010, t),
                sym("USDJPY", 150.09, 150.10, t),
                sym("EURJPY", 165.25, 165.26, t)));
        e1.sync(t);
        EvalResult r1 = e1.evalAt(t, 8000, 1.76, 1.0, pt);
        System.out.println("COHERENT  : net=" + (r1 == null ? "null" : String.format("%.4f", r1.netUsd))
                + " (attendu < 0)");
        if (r1 != null) decode(pt, r1.pathIdx);

        // ---- CAS 2 : bid EURJPY gonfle +0.75 JPY -> arb USD->EUR->JPY->USD ----
        // rate = 166.00/(1.1010*150.10) = 1.00448 -> ddd ~ +44.8 $
        Engine e2 = new Engine(java.util.List.of(
                sym("EURUSD", 1.1009, 1.1010, t),
                sym("USDJPY", 150.09, 150.10, t),
                sym("EURJPY", 166.00, 166.01, t)));
        e2.sync(t);
        EvalResult r2 = e2.evalAt(t, 8000, 1.76, 1.0, pt);
        System.out.println("INJECTE   : net=" + (r2 == null ? "null" : String.format("%.4f", r2.netUsd))
                + " (attendu ~ +40 $)");
        if (r2 != null) decode(pt, r2.pathIdx);

        // ---- CAS 3 : inversion dans l'autre sens (ask EURJPY casse) ----
        // USD->JPY->EUR->USD via bid USDJPY haut? Non: ask EURJPY basse.
        Engine e3 = new Engine(java.util.List.of(
                sym("EURUSD", 1.1009, 1.1010, t),
                sym("USDJPY", 150.09, 150.10, t),
                sym("EURJPY", 164.30, 164.31, t)));
        e3.sync(t);
        EvalResult r3 = e3.evalAt(t, 8000, 1.76, 1.0, pt);
        System.out.println("INVERSE   : net=" + (r3 == null ? "null" : String.format("%.4f", r3.netUsd))
                + " (attendu > 0 par le chemin inverse)");
        if (r3 != null) decode(pt, r3.pathIdx);

        boolean pass = r1 != null && r1.netUsd < 0
                && r2 != null && r2.netUsd > 30
                && r3 != null && r3.netUsd > 30;
        System.out.println(pass ? "VERDICT: MOTEUR SAIN — detecte l'edge injecte"
                                : "VERDICT: ANOMALIE — le moteur rate une opportunite reelle");
    }
}
