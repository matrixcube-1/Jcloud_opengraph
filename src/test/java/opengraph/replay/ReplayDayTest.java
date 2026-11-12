package opengraph.replay;

import static org.junit.jupiter.api.Assertions.*;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import opengraph.core.DataLoader;
import opengraph.core.Engine;
import opengraph.core.PathTables;
import opengraph.core.SymbolSeries;
import opengraph.core.Universe;
import org.junit.jupiter.api.Test;

/** Tests du module replay (port de optuna_duka::replay_day). */
class ReplayDayTest {

    static final long DAY0 = DataLoader.epochMs("2026-10-22");

    /**
     * Livre synthetique : 200 ticks par symbole a t = DAY0+100k ms,
     * distorsion CADJPY (mid*0.995) a partir de k=120, volumes 1e9
     * (liquidite infinie => fills complets).
     */
    static List<SymbolSeries> bookDay() {
        double[] ups = opengraph.core.PathsEvalTest.ups();
        List<SymbolSeries> out = new ArrayList<>();
        for (String s : Universe.FX) {
            int a = Universe.ci(s.substring(0, 3));
            int b = Universe.ci(s.substring(3, 6));
            double mid = ups[a] / ups[b];
            int n = 200;
            long[] ts = new long[n];
            double[] bids = new double[n];
            double[] asks = new double[n];
            double[] avol = new double[n];
            double[] bvol = new double[n];
            Arrays.fill(avol, 1e9);
            Arrays.fill(bvol, 1e9);
            for (int k = 0; k < n; k++) {
                double m = s.equals("CADJPY") && k >= 120
                        ? mid * 0.995 : mid;
                ts[k] = DAY0 + 100L * k;
                bids[k] = m * 0.9999;
                asks[k] = m * 1.0001;
            }
            out.add(new SymbolSeries(s, ts, bids, asks, avol, bvol));
        }
        return out;
    }

    // --- basketEdges ---------------------------------------------------

    /** Chemin 1-jambe USD->JPY : panier = {USDJPY} seul. */
    @Test
    void basket_edges_single_leg_usd() {
        PathTables pt = PathTables.build();
        int usdJpy = indexOf("USDJPY");
        int pi = findPath(pt, 1, new int[] {usdJpy});
        int[] be = ReplayDay.basketEdges(pt, pi);
        assertArrayEquals(new int[] {usdJpy}, be);
    }

    /** Chemin [USDJPY, CADJPY] : panier = jambes + conversion USDCAD. */
    @Test
    void basket_edges_adds_usd_exit_conversion() {
        PathTables pt = PathTables.build();
        int usdJpy = indexOf("USDJPY");
        int cadJpy = indexOf("CADJPY");
        int usdCad = indexOf("USDCAD");
        int pi = findPath(pt, 2, new int[] {usdJpy, cadJpy});
        int[] be = ReplayDay.basketEdges(pt, pi);
        assertArrayEquals(new int[] {
                Math.min(usdJpy, Math.min(cadJpy, usdCad)),
                mid3(usdJpy, cadJpy, usdCad),
                Math.max(usdJpy, Math.max(cadJpy, usdCad))}, be);
        assertEquals(3, be.length);
    }

    /** Chemin partant de EUR : panier inclut l'entree EURUSD. */
    @Test
    void basket_edges_adds_usd_entry_conversion() {
        PathTables pt = PathTables.build();
        // chemin EUR->JPY->USD : jambes EURJPY + USDJPY, end=USD
        int eurJpy = indexOf("EURJPY");
        int usdJpy = indexOf("USDJPY");
        int eurUsd = indexOf("EURUSD");
        int pi = findPath(pt, 2, new int[] {eurJpy, usdJpy});
        int[] be = ReplayDay.basketEdges(pt, pi);
        assertEquals(3, be.length);
        List<Integer> l = new ArrayList<>();
        for (int e : be) {
            l.add(e);
        }
        assertTrue(l.contains(eurJpy) && l.contains(usdJpy)
                && l.contains(eurUsd));
    }

    static int mid3(int a, int b, int c) {
        int[] v = {a, b, c};
        Arrays.sort(v);
        return v[1];
    }

    static int indexOf(String sym) {
        for (int i = 0; i < Universe.FX.length; i++) {
            if (Universe.FX[i].equals(sym)) {
                return i;
            }
        }
        return -1;
    }

    static int findPath(PathTables pt, int len, int[] edges) {
        edges = edges.clone();
        Arrays.sort(edges);
        for (int pi = 0; pi < pt.pL.length; pi++) {
            if (pt.pL[pi] != len) {
                continue;
            }
            int[] mine = Arrays.copyOf(pt.pE[pi], len);
            Arrays.sort(mine);
            if (Arrays.equals(mine, edges)) {
                return pi;
            }
        }
        fail("chemin non trouve");
        return -1;
    }

    // --- dayData ---------------------------------------------------------

    /** ev = union triee, symTs filtres au jour, seenMin exact. */
    @Test
    void day_data_filters_day_and_computes_seen_min() {
        Engine eng = new Engine(bookDay());
        ReplayDay.DayData dd = ReplayDay.dayData(eng, "2026-10-22");
        assertEquals(200, dd.ev.length);
        for (int i = 1; i < dd.ev.length; i++) {
            assertTrue(dd.ev[i] > dd.ev[i - 1]);
        }
        // tous les symboles ont des ticks identiques : seenMin[i] = i
        assertEquals(0, dd.seenMin[0]);
        assertEquals(99, dd.seenMin[99]);
        assertEquals(199, dd.seenMin[199]);
        // un jour sans donnees
        ReplayDay.DayData empty = ReplayDay.dayData(eng, "2026-10-23");
        assertEquals(0, empty.ev.length);
    }

    /** ticks hors-journee ignores par le filtre. */
    @Test
    void day_data_excludes_other_days() {
        List<SymbolSeries> book = new ArrayList<>(bookDay());
        // ajoute 3 ticks le lendemain sur EURUSD seulement
        SymbolSeries e = book.get(0);
        int n = e.len();
        long[] ts = Arrays.copyOf(e.ts, n + 3);
        double[] bid = Arrays.copyOf(e.bid, n + 3);
        double[] ask = Arrays.copyOf(e.ask, n + 3);
        double[] av = Arrays.copyOf(e.avol, n + 3);
        double[] bv = Arrays.copyOf(e.bvol, n + 3);
        for (int k = 0; k < 3; k++) {
            ts[n + k] = DAY0 + 86_400_000L + 1_000L * k;
            bid[n + k] = bid[n - 1];
            ask[n + k] = ask[n - 1];
            av[n + k] = 1e9;
            bv[n + k] = 1e9;
        }
        book.set(0, new SymbolSeries(e.sym, ts, bid, ask, av, bv));
        Engine eng = new Engine(book);
        ReplayDay.DayData d1 = ReplayDay.dayData(eng, "2026-10-22");
        ReplayDay.DayData d2 = ReplayDay.dayData(eng, "2026-10-23");
        assertEquals(200, d1.ev.length);
        assertEquals(3, d2.ev.length);
    }

    // --- replayDay -----------------------------------------------------

    /** Distorsion plantee -> au moins un trade ; deux runs identiques. */
    @Test
    void replay_day_trades_distortion_deterministically() {
        Engine eng = new Engine(bookDay());
        PathTables pt = PathTables.build();
        ReplayDay.DayData dd = ReplayDay.dayData(eng, "2026-10-22");
        Engine.Params p = new Engine.Params(5_000.0, 10.0, 1.0, -30.0,
                ReplayDay.LAT_MS, 0.8, 0.5, 0.0, false);
        double[] r1 = ReplayDay.replayDay(eng, pt, dd, p);
        double[] r2 = ReplayDay.replayDay(eng, pt, dd, p);
        assertTrue(r1[1] >= 1, "trades attendus: " + Arrays.toString(r1));
        assertArrayEquals(r1, r2, 0.0, "replay non deterministe");
        assertTrue(Double.isFinite(r1[0]));
    }

    /** dddMin tres haut -> jamais de trade (gate net). */
    @Test
    void replay_day_high_ddd_blocks_trades() {
        Engine eng = new Engine(bookDay());
        PathTables pt = PathTables.build();
        ReplayDay.DayData dd = ReplayDay.dayData(eng, "2026-10-22");
        Engine.Params p = new Engine.Params(5_000.0, 10.0, 1.0, 999_999.0,
                ReplayDay.LAT_MS, 0.8, 0.5, 0.0, false);
        double[] r = ReplayDay.replayDay(eng, pt, dd, p);
        assertEquals(0, r[1]);
        assertEquals(0.0, r[0]);
    }

    /** Warm-up : aucun eval avant 100 ticks par symbole. */
    @Test
    void replay_day_respects_warmup() {
        // livre a 99 ticks seulement : aucun eval possible
        List<SymbolSeries> book = bookDay();
        List<SymbolSeries> short99 = new ArrayList<>();
        for (SymbolSeries s : book) {
            int n = 99;
            short99.add(new SymbolSeries(s.sym,
                    Arrays.copyOf(s.ts, n), Arrays.copyOf(s.bid, n),
                    Arrays.copyOf(s.ask, n), Arrays.copyOf(s.avol, n),
                    Arrays.copyOf(s.bvol, n)));
        }
        Engine eng = new Engine(short99);
        PathTables pt = PathTables.build();
        ReplayDay.DayData dd = ReplayDay.dayData(eng, "2026-10-22");
        Engine.Params p = new Engine.Params(5_000.0, 10.0, 1.0, -30.0,
                ReplayDay.LAT_MS, 0.8, 0.5, 0.0, false);
        double[] r = ReplayDay.replayDay(eng, pt, dd, p);
        assertEquals(0, r[1]);
    }
}
