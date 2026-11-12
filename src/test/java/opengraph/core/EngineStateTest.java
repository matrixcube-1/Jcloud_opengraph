package opengraph.core;

import static org.junit.jupiter.api.Assertions.*;

import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;

/**
 * Miroirs de engine.rs::tests — memes valeurs d'assertion que le Rust.
 */
class EngineStateTest {

    private static SymbolSeries series(String sym, double[][] ticks) {
        int n = ticks.length;
        long[] ts = new long[n];
        double[] bid = new double[n];
        double[] ask = new double[n];
        double[] avol = new double[n];
        double[] bvol = new double[n];
        for (int i = 0; i < n; i++) {
            ts[i] = (long) ticks[i][0];
            bid[i] = ticks[i][1];
            ask[i] = ticks[i][2];
            avol[i] = 1.0;
            bvol[i] = 1.0;
        }
        return new SymbolSeries(sym, ts, bid, ask, avol, bvol);
    }

    /** T1 : sync avant le 1er tick -> aucune quote exposee (pas de futur). */
    @Test
    void t1_no_future_quote_on_early_sync() {
        Engine e = new Engine(List.of(
                series("EURUSD", new double[][] {
                    {1_000, 1.0999, 1.1001}, {2_000, 1.1002, 1.1004}}),
                series("USDCAD", new double[][] {{5_000, 1.3499, 1.3501}})));
        e.sync(500);
        int i = e.sidx.get("EURUSD");
        assertTrue(Double.isNaN(e.bid[i]));
        assertEquals(Engine.NO_TICK, e.lastT[i]);
        e.sync(1_500);
        assertEquals(1_000, e.lastT[i]);
        assertEquals(1.0999, e.bid[i]);
        int j = e.sidx.get("USDCAD");
        assertEquals(Engine.NO_TICK, e.lastT[j]);
    }

    /** T4 : rembobinage — sync(t2) puis sync(t1<t2) ne fige rien. */
    @Test
    void t4_rewind_reexposes_past() {
        Engine e = new Engine(List.of(series("EURUSD", new double[][] {
            {1_000, 1.0999, 1.1001}, {9_000, 1.2000, 1.2002}})));
        e.sync(9_500);
        int i = e.sidx.get("EURUSD");
        assertEquals(9_000, e.lastT[i]);
        e.sync(1_500);
        assertEquals(1_000, e.lastT[i]);
        assertEquals(1.0999, e.bid[i]);
    }

    /** Fraicheur bilaterale : future rejetee, trop vieille rejetee. */
    @Test
    void t3_bilateral_freshness() {
        Engine e = new Engine(List.of(
                series("EURUSD", new double[][] {{1_000, 1.0, 1.001}})));
        e.sync(1_000);
        int i = e.sidx.get("EURUSD");
        assertTrue(e.ok(i, 1_200, 500.0));
        assertFalse(e.ok(i, 2_000, 500.0));
        e.lastT[i] = 2_000;
        assertFalse(e.ok(i, 1_500, 5_000.0));
    }

    /** quoteAt : dichotomie pure, frontieres incluses/exclues. */
    @Test
    void quote_at_boundaries() {
        Engine e = new Engine(List.of(series("EURUSD", new double[][] {
            {1_000, 1.0, 1.001}, {2_000, 1.1, 1.101}, {3_000, 1.2, 1.201}})));
        int i = e.sidx.get("EURUSD");
        assertNull(e.quoteAt(i, 999));
        assertEquals(1_000, e.quoteAt(i, 1_000)[0]); // inclusif
        assertEquals(1_000, e.quoteAt(i, 1_999)[0]);
        assertEquals(3_000, e.quoteAt(i, 3_500)[0]);
    }

    /** Ordre des evenements a ts egal : tri stable = ordre de la table FX. */
    @Test
    void ev_order_at_equal_timestamps_follows_fx_table() {
        Engine e = new Engine(List.of(
                series("NZDCHF", new double[][] {
                    {1_000, 0.5, 0.501}, {1_000, 0.5, 0.501}}),
                series("EURUSD", new double[][] {
                    {1_000, 1.1, 1.101}, {1_000, 1.1, 1.101}}),
                series("CADJPY", new double[][] {
                    {1_000, 110.0, 110.1}, {1_000, 110.0, 110.1}})));
        List<Long> at1000 = new ArrayList<>();
        for (long[] ev : e.ev) {
            if (ev[0] == 1_000) {
                at1000.add(ev[1]);
            }
        }
        int eu = e.sidx.get("EURUSD");
        int cj = e.sidx.get("CADJPY");
        int nc = e.sidx.get("NZDCHF");
        List<Long> expected = List.of((long) eu, (long) eu, (long) cj,
                (long) cj, (long) nc, (long) nc);
        assertEquals(expected, at1000);
        e.sync(1_000);
        for (long lt : e.lastT) {
            assertTrue(lt <= 1_000);
        }
    }

    /** Quote croisee ou non finie : ni publiee ni dans la mediane. */
    @Test
    void ingest_rejects_crossed_or_nonfinite_quotes() {
        Engine e = Engine.live();
        int si = e.sidx.get("EURUSD");
        e.ingest(si, new Quote(1_000, 1.10, 1.102, 1.0));
        e.ingest(si, new Quote(2_000, 1.105, 1.104, 1.0)); // croisee
        e.ingest(si, new Quote(3_000, Double.NaN, 1.20, 1.0)); // NaN
        assertEquals(1_000, e.lastT[si], "quote invalide publiee");
        assertEquals(0.002, e.med[si], 1e-12, "mediane corrompue");
    }

    /** ingest : mediane incrementale = mediane exacte ; ts egal remplace
     * la quote sans re-pousser le spread. */
    @Test
    void ingest_live_updates_state_and_median() {
        Engine e = Engine.live();
        int si = e.sidx.get("EURUSD");
        e.ingest(si, new Quote(1_000, 1.10, 1.102, 1.0));
        assertEquals(1_000, e.lastT[si]);
        assertEquals(0.002, e.med[si], 1e-12);
        e.ingest(si, new Quote(2_000, 1.10, 1.104, 1.0));
        e.ingest(si, new Quote(3_000, 1.10, 1.103, 1.0));
        assertEquals(0.003, e.med[si], 1e-12);
        // ts egal : quote remplacee, spread du tick recu compte quand meme
        e.ingest(si, new Quote(3_000, 1.10, 1.10, 1.0));
        assertEquals(3_000, e.lastT[si]);
        assertEquals(1.10, e.ask[si]);
        assertEquals(3, e.data.get(si).len());
        assertEquals(0.0025, e.med[si], 1e-12);
    }

    /** MED_CUTOFF (port de engine.rs) : la mediane n'accumule que les ticks
     * <= cutoff — sinon le holdout fuit dans le cap spread_cap x med. */
    @Test
    void med_until_excludes_post_cutoff_ticks() {
        Engine e = Engine.live();
        int si = e.sidx.get("EURUSD");
        e.ingest(si, new Quote(1_000, 1.10, 1.102, 1.0));
        e.ingest(si, new Quote(2_000, 1.10, 1.104, 1.0));
        e.setMedUntil(2_000);
        assertEquals(0.003, e.med[si], 1e-12);
        // tick post-cutoff : etat publie mais spread hors mediane
        e.ingest(si, new Quote(3_000, 1.10, 1.15, 1.0));
        assertEquals(1.15, e.ask[si]);
        assertEquals(0.003, e.med[si], 1e-12, "holdout dans la mediane");
    }

    /** setMedUntil recalcule sur les series chargees (replay) : un engine
     * contenant in-sample + holdout expose la mediane in-sample seule. */
    @Test
    void med_until_recomputes_over_loaded_series() {
        Engine e = new Engine(List.of(
                series("EURUSD", new double[][] {
                        {1_000, 1.10, 1.102},
                        {2_000, 1.10, 1.104},
                        {3_000, 1.10, 1.20}})));
        assertEquals(0.004, e.med[e.sidx.get("EURUSD")], 1e-12);
        e.setMedUntil(2_000);
        assertEquals(0.003, e.med[e.sidx.get("EURUSD")], 1e-12);
    }

    /** OG2-D1 : un spread NaN/infini ne doit jamais entrer dans la mediane. */
    @Test
    void spread_median_ignores_non_finite() {
        SpreadMedian m = new SpreadMedian();
        for (double x : new double[] {0.001, 0.003, Double.NaN,
                Double.NaN, 0.002}) {
            m.push(x);
        }
        assertEquals(0.002, m.median(), 1e-15);
        assertTrue(Double.isFinite(m.median()));
    }

    /** usd_price : mid direct (EURUSD) et inverse (USDJPY), ok mask. */
    @Test
    void usd_price_direct_inverse() {
        Engine e = new Engine(List.of(
                series("EURUSD", new double[][] {{1_000, 1.10, 1.102}}),
                series("USDJPY", new double[][] {{1_000, 149.9, 150.1}})));
        e.sync(1_500);
        boolean[] ok = new boolean[e.data.size()];
        java.util.Arrays.fill(ok, true);
        double[] up = e.usdPrice(ok);
        assertEquals(1.0, up[0]);
        assertEquals(1.101, up[1], 1e-12);   // EUR : mid direct
        assertEquals(1.0 / 150.0, up[3], 1e-12); // JPY : 1/mid inverse
        boolean[] ok2 = ok.clone();
        ok2[e.sidx.get("EURUSD")] = false;
        assertTrue(Double.isNaN(e.usdPrice(ok2)[1]));
    }
}
