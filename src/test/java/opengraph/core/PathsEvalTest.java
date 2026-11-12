package opengraph.core;

import static org.junit.jupiter.api.Assertions.*;

import java.util.List;
import org.junit.jupiter.api.Test;

/**
 * Miroirs de paths.rs + eval.rs tests — helper book() porte a l'identique.
 */
public class PathsEvalTest {

    // --- helpers miroir du Rust ----------------------------------------

    public static double[] ups() {
        return new double[] {1.0, 1.10, 1.30, 1.0 / 150.0, 1.0 / 0.90,
            1.0 / 1.35, 0.65, 0.60};
    }

    /** Livre synthetique coherent : mid implicite up[base]/up[quote],
     * spread symetrique half en fraction. distort = {sym, mult} ou null. */
    public static List<SymbolSeries> book(double[] ups, double half,
                                   String[] distort) {
        java.util.List<SymbolSeries> out = new java.util.ArrayList<>();
        for (String s : Universe.FX) {
            int a = Universe.ci(s.substring(0, 3));
            int b = Universe.ci(s.substring(3, 6));
            double mid = ups[a] / ups[b];
            if (distort != null && distort[0].equals(s)) {
                mid *= Double.parseDouble(distort[1]);
            }
            double bid = mid * (1.0 - half);
            double ask = mid * (1.0 + half);
            out.add(new SymbolSeries(s, new long[] {1_000},
                    new double[] {bid}, new double[] {ask},
                    new double[] {1.0}, new double[] {1.0}));
        }
        return out;
    }

    // --- paths.rs ------------------------------------------------------

    /** Verrou de regression : exactement 2 072 chemins FX ouverts. */
    @Test
    void fx_path_count_is_2072() {
        Paths.EnumResult er = Paths.enumPaths();
        assertEquals(2072, er.paths.size());
        assertEquals(2072, er.starts.length);
        for (int[] p : er.paths) {
            assertTrue(p.length >= 1 && p.length <= 3);
        }
    }

    /** Les tables propagent le noeud correctement ; pS/pEnd coherents. */
    @Test
    void tables_node_propagation() {
        PathTables t = PathTables.build();
        assertEquals(2072, t.len());
        for (int pi = 0; pi < t.len(); pi++) {
            int node = t.pS[pi];
            for (int li = 0; li < t.pL[pi]; li++) {
                int[] ab = Universe.pairNodes(t.pE[pi][li]);
                assertEquals(node, t.pDir[pi][li] == 1 ? ab[0] : ab[1]);
                node = t.pDir[pi][li] == 1 ? ab[1] : ab[0];
            }
            assertEquals(node, t.pEnd[pi]);
        }
    }

    /** Chemin 1 jambe present : part des deux cotes de chaque arete. */
    @Test
    void one_leg_paths_both_sides() {
        PathTables t = PathTables.build();
        int one = 0;
        for (int pi = 0; pi < t.len(); pi++) {
            if (t.pL[pi] == 1) {
                one++;
            }
        }
        assertEquals(56, one); // 28 aretes x 2 sens
    }

    // --- eval.rs -------------------------------------------------------

    /** Livre coherent : aucune opportunite positive apres commissions. */
    @Test
    void eval_no_opportunity_on_consistent_book() {
        Engine e = new Engine(book(ups(), 1e-4, null));
        e.sync(1_500);
        PathTables pt = PathTables.build();
        EvalResult r = e.evalAt(1_500, 5_000.0, 10.0, 1.0, pt);
        assertNotNull(r);
        assertTrue(r.netUsd < 0.0, "livre coherent: net devrait etre < 0");
    }

    /** Distorsion CADJPY -0,5 % sur l'ask -> chemin JPY->CAD gagnant. */
    @Test
    void eval_finds_distorted_edge() {
        double[] ups = ups();
        Engine e = new Engine(book(ups, 1e-4, new String[] {"CADJPY", "0.995"}));
        e.sync(1_500);
        PathTables pt = PathTables.build();
        EvalResult r = e.evalAt(1_500, 5_000.0, 10.0, 1.0, pt);
        assertNotNull(r);

        int[] path = pt.paths.get(r.pathIdx);
        int iUj = indexFx("USDJPY");
        int iCj = indexFx("CADJPY");
        assertEquals(2, path.length);
        assertEquals(iUj, path[0]);
        assertEquals(iCj, path[1]);
        assertEquals(1, pt.pDir[r.pathIdx][0]);
        assertEquals(-1, pt.pDir[r.pathIdx][1]);
        assertEquals(0, pt.pS[r.pathIdx]);  // s0 = USD
        assertEquals(5, pt.pEnd[r.pathIdx]); // end = CAD

        double midCadjpy = ups[5] / ups[3] * 0.995;
        double askCadjpy = midCadjpy * 1.0001;
        double bidUsdjpy = (ups[0] / ups[3]) * (1.0 - 1e-4);
        double askUsdcad = (ups[0] / ups[5]) * (1.0 + 1e-4);
        double rate = bidUsdjpy * (1.0 / askCadjpy) * (1.0 / askUsdcad);
        double usdOut = Fees.NOTIONAL_USD * rate;
        double comm = 2.0 * 3.0 * Fees.commUsd();
        double unwind = 3.0 * 2e-4 * Fees.NOTIONAL_USD;
        double expected = usdOut - Fees.NOTIONAL_USD - comm - unwind;
        assertEquals(expected, r.netUsd, 1e-6);
        assertTrue(r.netUsd > 0.0);
    }

    /** Symbole sans tick publie -> ses chemins ineligibles. */
    @Test
    void eval_rejects_stale_paths() {
        List<SymbolSeries> series = book(ups(), 1e-4,
                new String[] {"CADJPY", "0.995"});
        for (SymbolSeries s : series) {
            if (s.sym.equals("AUDUSD")) {
                s.truncate(0);
            }
        }
        Engine e = new Engine(series);
        e.sync(1_500);
        PathTables pt = PathTables.build();
        EvalResult r = e.evalAt(1_500, 5_000.0, 10.0, 1.0, pt);
        if (r != null) {
            for (int li = 0; li < pt.pL[r.pathIdx]; li++) {
                assertNotEquals("AUDUSD",
                        Universe.FX[pt.pE[r.pathIdx][li]]);
            }
        }
    }

    /** spread_cap : spread dilate -> jambe invalidee. */
    @Test
    void eval_spread_cap_blocks_wide_quotes() {
        List<SymbolSeries> series = book(ups(), 1e-4,
                new String[] {"CADJPY", "0.995"});
        // remplacer CADJPY par des ticks normaux puis un tick dilate :
        // le median reste le spread normal -> le tick courant viole le cap
        for (int i = 0; i < series.size(); i++) {
            SymbolSeries s = series.get(i);
            if (s.sym.equals("CADJPY")) {
                double m = s.bid[0] / (1.0 - 1e-4);
                series.set(i, new SymbolSeries("CADJPY",
                        new long[] {900, 1_000, 1_100},
                        new double[] {m * 0.9999, m * 0.9999, m * 0.9999},
                        new double[] {m * 1.0001, m * 1.0001, m * 1.01},
                        new double[] {1.0, 1.0, 1.0},
                        new double[] {1.0, 1.0, 1.0}));
            }
        }
        Engine e = new Engine(series);
        e.sync(1_500);
        PathTables pt = PathTables.build();
        EvalResult r = e.evalAt(1_500, 5_000.0, 1.5, 1.0, pt);
        assertNotNull(r);
        for (int li = 0; li < pt.pL[r.pathIdx]; li++) {
            assertNotEquals("CADJPY", Universe.FX[pt.pE[r.pathIdx][li]]);
        }
    }

    private static int indexFx(String sym) {
        for (int i = 0; i < Universe.FX.length; i++) {
            if (Universe.FX[i].equals(sym)) {
                return i;
            }
        }
        throw new IllegalArgumentException(sym);
    }
}
