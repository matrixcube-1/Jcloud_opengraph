package opengraph.core;

import static org.junit.jupiter.api.Assertions.*;

import java.lang.management.ManagementFactory;
import java.util.Arrays;
import java.util.HashMap;
import java.util.Map;
import opengraph.core.Quote;
import org.junit.jupiter.api.Test;

/**
 * Garde-fous perf JCloud (runbook : calcul chemin < 1 ms p99, >= 10 k ticks/s)
 * + boucle chaude sans allocation (seul l'EvalResult retourne est alloue).
 */
class PerfBudgetTest {

    static final Map<String, Double> USD_VAL = new HashMap<>();
    static {
        USD_VAL.put("USD", 1.0);
        USD_VAL.put("EUR", 1.10);
        USD_VAL.put("GBP", 1.30);
        USD_VAL.put("JPY", 1.0 / 150.0);
        USD_VAL.put("CHF", 1.12);
        USD_VAL.put("CAD", 0.73);
        USD_VAL.put("AUD", 0.66);
        USD_VAL.put("NZD", 0.60);
    }

    /** Livre coherent (spread 1 bp) sur les 28 FX + 26 bouts, a l'instant t. */
    static void book(Engine e, long t) {
        for (String p : Universe.FX) {
            double mid = USD_VAL.get(p.substring(0, 3)) / USD_VAL.get(p.substring(3));
            e.ingest(e.sidx.get(p), new Quote(t, mid * (1 - 0.5e-4), mid * (1 + 0.5e-4), 1e6));
        }
        for (Bout b : BoutUniverse.BOUTS) {
            e.ingest(e.sidx.get(b.name), new Quote(t, 99.99, 100.01, 1e6));
        }
    }

    static long allocated() {
        return ((com.sun.management.ThreadMXBean) ManagementFactory.getThreadMXBean())
                .getThreadAllocatedBytes(Thread.currentThread().getId());
    }

    static double p99(double[] a) {
        double[] b = a.clone();
        Arrays.sort(b);
        return b[(int) (b.length * 0.99)];
    }

    final PathTables pt = PathTables.build();
    final ExtPathTables xt = ExtPathTables.build(pt);

    @Test
    void eval_hot_loop_does_not_allocate() {
        Engine e = Engine.live();
        book(e, 1_000);
        for (int i = 0; i < 3_000; i++) {
            assertNotNull(e.evalAt(1_000, 8000, 1.7, 1.0, pt));
            assertNotNull(e.evalBoutAt(1_000, 8000, 1.7, 1.0, pt, xt));
        }
        int n = 1_000;
        long a = allocated();
        for (int i = 0; i < n; i++) {
            e.evalAt(1_000, 8000, 1.7, 1.0, pt);
        }
        long perAt = (allocated() - a) / n;
        a = allocated();
        for (int i = 0; i < n; i++) {
            e.evalBoutAt(1_000, 8000, 1.7, 1.0, pt, xt);
        }
        long perBout = (allocated() - a) / n;
        assertTrue(perAt <= 64, "evalAt alloue " + perAt + " o/appel");
        assertTrue(perBout <= 64, "evalBoutAt alloue " + perBout + " o/appel");
    }

    @Test
    void eval_p99_under_jcloud_budget() {
        Engine e = Engine.live();
        book(e, 1_000);
        for (int i = 0; i < 3_000; i++) {
            e.evalAt(1_000, 8000, 1.7, 1.0, pt);
            e.evalBoutAt(1_000, 8000, 1.7, 1.0, pt, xt);
        }
        int n = 2_000;
        double[] at = new double[n];
        double[] bo = new double[n];
        for (int i = 0; i < n; i++) {
            long s = System.nanoTime();
            e.evalAt(1_000, 8000, 1.7, 1.0, pt);
            at[i] = (System.nanoTime() - s) / 1e3;
            s = System.nanoTime();
            e.evalBoutAt(1_000, 8000, 1.7, 1.0, pt, xt);
            bo[i] = (System.nanoTime() - s) / 1e3;
        }
        assertTrue(p99(at) < 1_000, "evalAt p99=" + p99(at) + " us");
        assertTrue(p99(bo) < 1_000, "evalBoutAt p99=" + p99(bo) + " us");
    }

    @Test
    void ingest_does_not_allocate_per_tick() {
        Engine e = Engine.live();
        book(e, 1_000);
        int si = e.sidx.get("EURUSD");
        for (int i = 0; i < 20_000; i++) {
            e.ingest(si, new Quote(2_000 + i, 1.1, 1.1001, 1e6));
        }
        int n = 10_000;
        Quote[] qs = new Quote[n];
        for (int i = 0; i < n; i++) {
            qs[i] = new Quote(20_000 + i, 1.1, 1.1001, 1e6);
        }
        long a = allocated();
        for (int i = 0; i < n; i++) {
            e.ingest(si, qs[i]);
        }
        long perTick = (allocated() - a) / n;
        assertTrue(perTick <= 8, "ingest alloue " + perTick + " o/tick");
    }

    @Test
    void ingest_throughput_above_10k_ticks_per_s() {
        Engine e = Engine.live();
        book(e, 1_000);
        int si = e.sidx.get("EURUSD");
        int n = 200_000;
        long s = System.nanoTime();
        for (int i = 0; i < n; i++) {
            double b = 1.1 + (i % 7) * 1e-5;
            e.ingest(si, new Quote(2_000 + i, b, b + 1e-4, 1e6));
        }
        double tps = n / ((System.nanoTime() - s) / 1e9);
        assertTrue(tps >= 10_000, "ingest " + tps + " ticks/s");
    }
}
