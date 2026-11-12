package opengraph.core;

import java.util.Arrays;

/**
 * Serie de ticks ordonnee d'un symbole — layout SoA miroir du POC.
 * Invariants : {@code ts} croissant non strict ; {@code bid <= ask}.
 * Port de engine.rs::SymbolSeries.
 */
public final class SymbolSeries {
    public final String sym;
    public long[] ts;
    public double[] bid;
    public double[] ask;
    /** Volume par cote (millions de devise de base, Dukas).
     * avol = cote ask, bvol = cote bid — la garde volume lit le cote
     * que l'ordre atteint ; quoteAt expose max(avol, bvol). */
    public double[] avol;
    public double[] bvol;
    private int len;

    public SymbolSeries(String sym) {
        this(sym, new long[64], new double[64], new double[64],
                new double[64], new double[64], 0);
    }

    public SymbolSeries(String sym, long[] ts, double[] bid, double[] ask,
                        double[] avol, double[] bvol) {
        this(sym, ts, bid, ask, avol, bvol, ts.length);
    }

    private SymbolSeries(String sym, long[] ts, double[] bid, double[] ask,
                         double[] avol, double[] bvol, int len) {
        this.sym = sym;
        this.ts = ts;
        this.bid = bid;
        this.ask = ask;
        this.avol = avol;
        this.bvol = bvol;
        this.len = len;
    }

    public int len() {
        return len;
    }

    public boolean isEmpty() {
        return len == 0;
    }

    /** Dernier timestamp de la serie, NO_TICK si vide. */
    public long lastTs() {
        return len == 0 ? Engine.NO_TICK : ts[len - 1];
    }

    /** Ajoute un tick en fin de serie (croissance amortie). */
    public void push(long t, double b, double a, double av, double bv) {
        ensure(len + 1);
        ts[len] = t;
        bid[len] = b;
        ask[len] = a;
        avol[len] = av;
        bvol[len] = bv;
        len++;
    }

    /** Remplace le dernier tick (re-emission meme instant, cf. ingest). */
    public void replaceLast(double b, double a, double av, double bv) {
        int i = len - 1;
        bid[i] = b;
        ask[i] = a;
        avol[i] = av;
        bvol[i] = bv;
    }

    /** Tronque la serie a {@code n} ticks (construction de tests). */
    public void truncate(int n) {
        if (n < len) {
            len = n;
        }
    }

    private void ensure(int cap) {
        if (cap <= ts.length) {
            return;
        }
        int ncap = Math.max(cap, ts.length * 2);
        ts = Arrays.copyOf(ts, ncap);
        bid = Arrays.copyOf(bid, ncap);
        ask = Arrays.copyOf(ask, ncap);
        avol = Arrays.copyOf(avol, ncap);
        bvol = Arrays.copyOf(bvol, ncap);
    }

    /**
     * Dernier indice de tick {@code <= t} (partition_point Rust),
     * -1 si aucun. ts croissant non strict.
     */
    public int partitionPoint(long t) {
        int lo = 0, hi = len; // premiere position avec ts[idx] > t
        while (lo < hi) {
            int mid = (lo + hi) >>> 1;
            if (ts[mid] <= t) {
                lo = mid + 1;
            } else {
                hi = mid;
            }
        }
        return lo - 1;
    }

    /** Variante flottante (t_fill = t_prev + lat_ms est non entier). */
    public int partitionPointF(double t) {
        int lo = 0, hi = len;
        while (lo < hi) {
            int mid = (lo + hi) >>> 1;
            if (ts[mid] <= t) {
                lo = mid + 1;
            } else {
                hi = mid;
            }
        }
        return lo - 1;
    }
}
