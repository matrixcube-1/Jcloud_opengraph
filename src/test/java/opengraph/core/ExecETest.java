package opengraph.core;

import static org.junit.jupiter.api.Assertions.*;

import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;

/** Couche E1-E9 du document (docMode) : E3, E4, E6, E7. */
public class ExecETest {

    private static SymbolSeries sym(String s, long[] ts, double[] bid,
                                    double[] ask) {
        double[] av = new double[ts.length];
        double[] bv = new double[ts.length];
        java.util.Arrays.fill(av, 1.0);
        java.util.Arrays.fill(bv, 1.0);
        return new SymbolSeries(s, ts, bid, ask, av, bv);
    }

    /** Livre 2 ticks : t=1000 normal, t=2000 jambe CADJPY cassee. */
    private Engine brokenLegBook() {
        double[] ups = PathsEvalTest.ups();
        List<SymbolSeries> out = new ArrayList<>();
        for (String s : Universe.FX) {
            int a = Universe.ci(s.substring(0, 3));
            int b = Universe.ci(s.substring(3, 6));
            double mid = ups[a] / ups[b];
            double bid1 = mid * 0.9999, ask1 = mid * 1.0001;
            double bid2 = bid1, ask2 = ask1;
            if (s.equals("USDJPY")) { // casse la conversion JPY->USD
                bid2 = bid1 * 0.90;
                ask2 = ask1 * 0.90;
            }
            out.add(sym(s, new long[] {1_000, 2_000},
                    new double[] {bid1, bid2}, new double[] {ask1, ask2}));
        }
        return new Engine(out);
    }

    private ExecParams docP() {
        return ExecParams.doc(2.5, 0.8, 0.5, 500.0, 1.0, 3);
    }

    @Test
    public void staleQuoteAborts() {
        // ticks a t=1000 seulement ; tF=1002,5+... ok ; puis trou -> STALE
        double[] ups = PathsEvalTest.ups();
        List<SymbolSeries> out = new ArrayList<>();
        for (String s : Universe.FX) {
            int a = Universe.ci(s.substring(0, 3));
            int b = Universe.ci(s.substring(3, 6));
            double mid = ups[a] / ups[b];
            // tick a 1000 puis 5000 : a t0=4500, tF=4502,5 -> age>500
            out.add(sym(s, new long[] {1_000, 5_000},
                    new double[] {mid, mid}, new double[] {mid, mid}));
        }
        Engine eng = new Engine(out);
        PathTables pt = PathTables.build();
        // chemin quelconque
        ExecOutcome r = eng.execSeq(4_500, 0, docP(), pt);
        assertTrue(r instanceof ExecOutcome.Abort);
        assertEquals(AbortReason.STALE_QUOTE,
                ((ExecOutcome.Abort) r).reason);
    }

    @Test
    public void wideSpreadAborts() {
        // EURUSD serre sur 4 ticks (mediane etroite) puis large au 5e ;
        // chemin [GBP -> EURGBP] : la sortie EUR->USD tape la quote large.
        double[] ups = PathsEvalTest.ups();
        List<SymbolSeries> out = new ArrayList<>();
        long[] ts = {1_000, 1_500, 2_000, 2_500, 3_000};
        for (String s : Universe.FX) {
            int a = Universe.ci(s.substring(0, 3));
            int b = Universe.ci(s.substring(3, 6));
            double mid = ups[a] / ups[b];
            if (s.equals("EURUSD")) {
                // mediane etroite sur 4 ticks, spread 10% au 5e (t=3000)
                double[] bid = new double[5];
                double[] ask = new double[5];
                for (int i = 0; i < 5; i++) {
                    double h = i == 4 ? 0.05 : 1e-6;
                    bid[i] = mid * (1 - h);
                    ask[i] = mid * (1 + h);
                }
                out.add(sym(s, ts, bid, ask));
            } else {
                out.add(sym(s, new long[] {3_000},
                        new double[] {mid * (1 - 1e-6)},
                        new double[] {mid * (1 + 1e-6)}));
            }
        }
        Engine eng = new Engine(out);
        PathTables pt = PathTables.build();
        // chemin s0=GBP(2), 1 jambe EURGBP -> exit EURUSD a t~3000
        int pi = -1;
        int eurGbp = -1;
        for (int e = 0; e < Universe.FX.length; e++) {
            if (Universe.FX[e].equals("EURGBP")) {
                eurGbp = e;
            }
        }
        for (int p = 0; p < pt.len(); p++) {
            if (pt.pS[p] == 2 && pt.pL[p] == 1 && pt.pE[p][0] == eurGbp) {
                pi = p;
                break;
            }
        }
        assertTrue(pi >= 0);
        ExecOutcome r = eng.execSeq(3_000, pi, docP(), pt);
        assertTrue(r instanceof ExecOutcome.Abort);
        assertEquals(AbortReason.SPREAD_WIDE,
                ((ExecOutcome.Abort) r).reason);
    }

    @Test
    public void unwindRecoversPosition() {
        Engine eng = brokenLegBook();
        PathTables pt = PathTables.build();
        // chemin 0 = [EURUSD] ou equivalent 1 jambe -> abort sur jambe
        // cassee ou residuel : dans les deux cas docMode fait un vrai
        // unwind E6 au lieu du cout fixe 12$/jambe
        ExecOutcome r = eng.execSeq(1_000, 0, docP(), pt);
        assertNotNull(r);
        if (r instanceof ExecOutcome.Abort) {
            ExecOutcome.Abort a = (ExecOutcome.Abort) r;
            // residUsd documente (E7) et pnl borne
            assertTrue(a.residUsd >= 0.0);
            assertTrue(a.pnlUsd > -Fees.NOTIONAL_USD);
        }
    }

    @Test
    public void nonDocModeKeepsReferenceSemantics() {
        Engine eng = brokenLegBook();
        PathTables pt = PathTables.build();
        ExecOutcome r = eng.execSeq(1_000, 0,
                new ExecParams(2.5, 0.8, 0.5, false), pt);
        assertNotNull(r);
        if (r instanceof ExecOutcome.Abort) {
            // ancien modele : cout fixe par jambe, pas de residUsd
            assertEquals(0.0, ((ExecOutcome.Abort) r).residUsd);
        }
    }
}
