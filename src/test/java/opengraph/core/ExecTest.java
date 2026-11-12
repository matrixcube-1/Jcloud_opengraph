package opengraph.core;

import static org.junit.jupiter.api.Assertions.*;

import java.util.List;
import org.junit.jupiter.api.Test;

/**
 * Miroirs de exec.rs::tests — memes valeurs, memes tolerances (1e-12
 * la ou le Rust exige la quasi-egalite bit a bit).
 */
class ExecTest {

    static final ExecParams EP = new ExecParams(2.5, 0.8, 0.5, true);
    /** Latence nulle pour les tests settle (fills a la quote de decision). */
    static final ExecParams EP0 = new ExecParams(0.0, 0.8, 0.5, false);

    /** Livre avec CADJPY distordu ; ticks repetes pour le passage du temps. */
    static List<SymbolSeries> bookDistorted() {
        double[] ups = PathsEvalTest.ups();
        java.util.List<SymbolSeries> out = new java.util.ArrayList<>();
        for (String s : Universe.FX) {
            int a = Universe.ci(s.substring(0, 3));
            int b = Universe.ci(s.substring(3, 6));
            double mid = ups[a] / ups[b];
            if (s.equals("CADJPY")) {
                mid *= 0.995;
            }
            // meme quote a t=1000 et t=1002 : le fill a t+2,5ms voit le
            // tick 1002 (<= 1002.5) — le mouvement est capte
            out.add(new SymbolSeries(s, new long[] {1_000, 1_002},
                    new double[] {mid * 0.9999, mid * 0.9999},
                    new double[] {mid * 1.0001, mid * 1.0001},
                    new double[] {1.0, 1.0}, new double[] {1.0, 1.0}));
        }
        return out;
    }

    static int pathJpyCad(PathTables pt) {
        int eIdx = indexFx("CADJPY");
        for (int pi = 0; pi < pt.len(); pi++) {
            if (pt.paths.get(pi).length == 1
                    && pt.paths.get(pi)[0] == eIdx
                    && pt.pDir[pi][0] == -1
                    && pt.pS[pi] == Universe.ci("JPY")) {
                return pi;
            }
        }
        throw new IllegalStateException("chemin JPY->CAD introuvable");
    }

    static int indexFx(String sym) {
        for (int i = 0; i < Universe.FX.length; i++) {
            if (Universe.FX[i].equals(sym)) {
                return i;
            }
        }
        throw new IllegalArgumentException(sym);
    }

    /** Fill complet a la quote de fill : P&L recalcule a la main.
     * Panier JPY->CAD : [USDJPY -> CADJPY -> USDCAD] = 3 positions. */
    @Test
    void exec_full_fill_matches_hand_computation() {
        Engine e = new Engine(bookDistorted());
        PathTables pt = PathTables.build();
        int pi = pathJpyCad(pt);
        double[] ups = PathsEvalTest.ups();
        ExecOutcome r = e.execSeq(1_000, pi, EP, pt);
        assertNotNull(r);
        assertTrue(r instanceof ExecOutcome.Done, "attendu Done");
        ExecOutcome.Done d = (ExecOutcome.Done) r;
        double bidUj = (ups[0] / ups[3]) * 0.9999;
        double askCj = (ups[5] / ups[3] * 0.995) * 1.0001;
        double askUc = (ups[0] / ups[5]) * 1.0001;
        double usdEnd = Fees.NOTIONAL_USD * bidUj / askCj / askUc;
        double expected = usdEnd - Fees.NOTIONAL_USD
                - 6.0 * Fees.commUsd()
                - 3.0 * 2e-4 * Fees.NOTIONAL_USD;
        assertEquals(expected, d.pnlUsd, 1e-6);
        assertEquals(7.5, d.durMs, 1e-12); // 3 ordres x 2.5 ms
        assertEquals(3, d.filledLegs);
        assertEquals(0, d.partialLegs);
        assertTrue(d.pnlUsd > 0.0);
    }

    /** Prix adverse > 3.slip -> kill IOC ; l'entree USDJPY se deboucle. */
    @Test
    void exec_kill_ioc_on_adverse_move() {
        List<SymbolSeries> series = bookDistorted();
        for (SymbolSeries s : series) {
            if (s.sym.equals("CADJPY")) {
                s.push(1_004, s.bid[1], s.ask[1] * 1.01, 1.0, 1.0);
            }
        }
        Engine e = new Engine(series);
        PathTables pt = PathTables.build();
        int pi = pathJpyCad(pt);
        ExecOutcome r = e.execSeq(1_000, pi, EP, pt);
        assertNotNull(r);
        assertTrue(r instanceof ExecOutcome.Abort, "attendu Abort");
        ExecOutcome.Abort a = (ExecOutcome.Abort) r;
        assertEquals(AbortReason.KILL_IOC, a.reason);
        assertEquals(1, a.filledLegs); // entree USDJPY remplie
        // pnl = -cout(USDJPY) - comm ouverture = -(2,0 + 0,4) - 0,4
        assertEquals(-2.8, a.pnlUsd, 0.05);
    }

    /** Pas de tick <= t_fill du 2e ordre -> abort "no quote". */
    @Test
    void exec_no_quote_aborts() {
        List<SymbolSeries> series = bookDistorted();
        for (int i = 0; i < series.size(); i++) {
            SymbolSeries s = series.get(i);
            if (s.sym.equals("CADJPY")) {
                // plus de tick <= 1005
                series.set(i, new SymbolSeries("CADJPY",
                        new long[] {1_006, 1_007},
                        new double[] {s.bid[0], s.bid[1]},
                        new double[] {s.ask[0], s.ask[1]},
                        new double[] {1.0, 1.0},
                        new double[] {1.0, 1.0}));
            }
        }
        Engine e = new Engine(series);
        PathTables pt = PathTables.build();
        int pi = pathJpyCad(pt);
        ExecOutcome r = e.execSeq(1_000, pi, EP, pt);
        assertNotNull(r);
        assertTrue(r instanceof ExecOutcome.Abort, "attendu Abort");
        ExecOutcome.Abort a = (ExecOutcome.Abort) r;
        assertEquals(AbortReason.NO_QUOTE, a.reason);
        assertEquals(1, a.filledLegs);
        assertEquals(0, a.partialLegs);
        assertEquals(2.5, a.durMs, 1e-12); // abort a 1002.5
        assertEquals(-2.8, a.pnlUsd, 0.05);
    }

    /** Garde volume : tick de fill avec volume insuffisant -> q<q_min. */
    @Test
    void exec_volume_guard_rejects_thin_book() {
        List<SymbolSeries> series = bookDistorted();
        for (SymbolSeries s : series) {
            if (s.sym.equals("CADJPY")) {
                s.avol[1] = 0.005; // 5 K CAD ask < ~7,4 K requis a 10 K
            }
        }
        Engine e = new Engine(series);
        PathTables pt = PathTables.build();
        int pi = pathJpyCad(pt);
        ExecOutcome r = e.execSeq(1_000, pi, EP, pt);
        assertNotNull(r);
        assertTrue(r instanceof ExecOutcome.Abort,
                "attendu Abort (liquidite insuffisante)");
        assertEquals(AbortReason.BELOW_QMIN,
                ((ExecOutcome.Abort) r).reason);
    }

    /** Garde volume lit le cote atteint, pas max(avol,bvol). */
    @Test
    void exec_volume_guard_reads_hit_side_not_max() {
        List<SymbolSeries> series = bookDistorted();
        for (SymbolSeries s : series) {
            if (s.sym.equals("CADJPY")) {
                s.avol[1] = 0.005; // ask fino (cote atteint par le buy)
                s.bvol[1] = 50.0;  // bid fundo — max() esconderia a falta
            }
        }
        Engine e = new Engine(series);
        PathTables pt = PathTables.build();
        int pi = pathJpyCad(pt);
        ExecOutcome r = e.execSeq(1_000, pi, EP, pt);
        assertNotNull(r);
        assertTrue(r instanceof ExecOutcome.Abort,
                "ask_vol fino deve barrar mesmo com bid_vol fundo");
        assertEquals(AbortReason.BELOW_QMIN,
                ((ExecOutcome.Abort) r).reason);
    }

    /** Fill partiel 60 % : residuel au cash ; panier complete. */
    @Test
    void exec_partial_fill_marks_residual() {
        List<SymbolSeries> series = bookDistorted();
        for (SymbolSeries s : series) {
            if (s.sym.equals("CADJPY")) {
                // tick 1004 hausse ~1.5 bp -> frac 0.6 (slip=0.8bp)
                s.push(1_004, s.bid[1], s.ask[1] * (1.0 + 1.5e-4), 1.0, 1.0);
            }
        }
        Engine e = new Engine(series);
        PathTables pt = PathTables.build();
        int pi = pathJpyCad(pt);
        ExecOutcome r = e.execSeq(1_000, pi, EP, pt);
        assertNotNull(r);
        assertTrue(r instanceof ExecOutcome.Done,
                "attendu Done avec partiel");
        ExecOutcome.Done d = (ExecOutcome.Done) r;
        assertEquals(3, d.filledLegs);
        assertEquals(1, d.partialLegs);
    }

    // --- exec_settle : bouclage sur fills externes ---

    /** Premier chemin a n jambes (deterministe — ordre de la table). */
    static int pathNLegs(PathTables pt, int n) {
        for (int pi = 0; pi < pt.len(); pi++) {
            if (pt.pL[pi] == n) {
                return pi;
            }
        }
        throw new IllegalStateException();
    }

    /** exec_settle sur fills equivalents = exec_seq(lat=0) a l'arrondi. */
    @Test
    void settle_full_fills_matches_exec_seq_3_legs() {
        Engine e = new Engine(bookDistorted());
        PathTables pt = PathTables.build();
        int pi = pathNLegs(pt, 3);
        int[][] orders = e.ordersOf(pi, pt);
        assertNotNull(orders);
        int nOrd = orders.length;
        double[][] fills = new double[nOrd][];
        for (int i = 0; i < nOrd; i++) {
            int eI = orders[i][0];
            int from = orders[i][1];
            int si = e.fxSi[eI];
            int[] ab = Engine.edgeNodes(eI);
            double[] q = e.quoteAtF(si, 1_000.0);
            fills[i] = new double[] {1.0,
                    from == ab[0] ? q[1] : 1.0 / q[2]};
        }
        e.sync(1_000); // replay : etat a t0 (sync hors de exec_settle — C3)
        double[] s = e.execSettle(pi, fills, false, pt);
        assertNotNull(s);
        ExecOutcome r = e.execSeq(1_000, pi, EP0, pt);
        assertNotNull(r);
        assertTrue(r instanceof ExecOutcome.Done, "attendu Done");
        ExecOutcome.Done d = (ExecOutcome.Done) r;
        assertEquals(nOrd, (int) s[1]);
        assertEquals(0, (int) s[2]);
        assertEquals(nOrd, d.filledLegs);
        assertEquals(0, d.partialLegs);
        assertEquals(0.0, Math.abs(s[0] - d.pnlUsd)
                / Math.max(Math.abs(d.pnlUsd), 1.0), 1e-12);
    }

    /** Plus de fills que d'ordres du panier -> null, pas de crash. */
    @Test
    void settle_rejects_more_fills_than_legs() {
        Engine e = new Engine(bookDistorted());
        PathTables pt = PathTables.build();
        int pi = pathJpyCad(pt); // 1 jambe -> panier de 3 ordres
        e.sync(1_000);
        assertNull(e.execSettle(pi, new double[][] {
            {1.0, 1.0}, {1.0, 1.0}, {1.0, 1.0}, {1.0, 1.0}}, false, pt));
    }

    /** Abort apres positions remplies : pnl = -abort_cost + cash - comm. */
    @Test
    void settle_abort_matches_hand_computation() {
        Engine e = new Engine(bookDistorted());
        PathTables pt = PathTables.build();
        int pi = pathJpyCad(pt);
        e.sync(1_000);
        double[] s0 = e.execSettle(pi, new double[0][], true, pt);
        assertNotNull(s0);
        assertEquals(0.0, s0[0], 1e-12);
        assertEquals(0, (int) s0[1]);
        assertEquals(0, (int) s0[2]);

        // 1er ordre (entree USD->JPY via USDJPY) rempli puis abort.
        int siUj = e.fxSi[2]; // USDJPY = arete 2
        double[] q = e.quoteAtF(siUj, 1_000.0);
        double bidUj = q[1];
        double askUj = q[2];
        double[] s2 = e.execSettle(pi,
                new double[][] {{1.0, bidUj}}, true, pt);
        assertNotNull(s2);
        assertEquals(1, (int) s2[1]);
        double abortCost = e.med[siUj] / ((bidUj + askUj) * 0.5)
                * Fees.NOTIONAL_USD + Fees.commUsd();
        assertEquals(2.4, abortCost, 0.1);
        assertEquals(-abortCost - Fees.commUsd(), s2[0], 1e-9);
        // resid = qty JPY marquee a sa liquidation : 1/ask(USDJPY)
        assertEquals(Fees.NOTIONAL_USD * bidUj / askUj, s2[3], 1e-6);
    }

    /** Partiel 60 % via settle = meme residuel cash que exec_seq. */
    @Test
    void settle_partial_marks_residual_like_exec_seq() {
        List<SymbolSeries> series = bookDistorted();
        for (SymbolSeries s : series) {
            if (s.sym.equals("CADJPY")) {
                s.push(1_004, s.bid[1], s.ask[1] * (1.0 + 1.5e-4), 1.0, 1.0);
            }
        }
        Engine e = new Engine(series);
        PathTables pt = PathTables.build();
        int pi = pathJpyCad(pt);
        ExecParams ep = new ExecParams(2.5, EP0.tauSlipBp, EP0.qMin,
                EP0.volGuard);
        ExecOutcome r = e.execSeq(1_000, pi, ep, pt);
        assertNotNull(r);
        assertTrue(r instanceof ExecOutcome.Done, "attendu Done");
        double pnlUsd = r.pnl();

        int siUj = e.fxSi[2];
        int siCj = e.fxSi[22];
        int siUc = e.fxSi[5];
        double bidUj = e.quoteAtF(siUj, 1_000.0)[1];
        double askF = e.quoteAtF(siCj, 1_004.0)[2];
        double askUc = e.quoteAtF(siUc, 1_000.0)[2];
        e.sync(1_000);
        double[] s = e.execSettle(pi, new double[][] {
            {1.0, bidUj}, {0.6, 1.0 / askF}, {1.0, 1.0 / askUc}},
                false, pt);
        assertNotNull(s);
        assertEquals(1, (int) s[2]);
        assertEquals(0.0, Math.abs(s[0] - pnlUsd)
                / Math.max(Math.abs(pnlUsd), 1.0), 1e-12);
        assertTrue(s[3] > 0.0); // resid = qty USD final
    }

    /** startQty = NOTIONAL_USD — le panier demarre toujours en USD. */
    @Test
    void start_qty_is_notional_usd() {
        Engine e = new Engine(bookDistorted());
        PathTables pt = PathTables.build();
        int pi = pathJpyCad(pt);
        e.sync(1_000);
        Double q = e.startQty(pi, pt);
        assertNotNull(q);
        assertEquals(Fees.NOTIONAL_USD, q);
    }
}
