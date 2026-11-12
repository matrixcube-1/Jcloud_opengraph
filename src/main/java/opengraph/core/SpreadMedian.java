package opengraph.core;

/**
 * Mediane incrementale exacte (deux tas) — meme semantique que la mediane
 * par tri. Maintient med a jour en ingestion live sans recalcul O(n log n)
 * par tick. Port de engine.rs::SpreadMedian.
 *
 * Implementation sur double[] : aucune allocation par push (croissance
 * amortie), ~8 o/element au lieu de ~40 o avec PriorityQueue&lt;Double&gt;
 * — indispensable pour les sessions JCloud multi-jours.
 */
public final class SpreadMedian {
    private double[] lo = new double[64]; // max-heap : moitie basse
    private int nLo;
    private double[] hi = new double[64]; // min-heap : moitie haute
    private int nHi;

    public void push(double x) {
        if (!Double.isFinite(x)) {
            return; // NaN/inf corrompraient la mediane
        }
        if (nLo == 0 || x <= lo[0]) {
            pushLo(x);
        } else {
            pushHi(x);
        }
        if (nLo > nHi + 1) {
            pushHi(popLo());
        } else if (nHi > nLo) {
            pushLo(popHi());
        }
    }

    public double median() {
        if (nLo == 0) {
            return Double.NaN;
        }
        if (nLo > nHi) {
            return lo[0];
        }
        return 0.5 * (lo[0] + hi[0]);
    }

    private void pushLo(double x) {
        if (nLo == lo.length) {
            lo = java.util.Arrays.copyOf(lo, lo.length * 2);
        }
        int i = nLo++;
        while (i > 0) {
            int p = (i - 1) >>> 1;
            if (lo[p] >= x) {
                break;
            }
            lo[i] = lo[p];
            i = p;
        }
        lo[i] = x;
    }

    private double popLo() {
        double top = lo[0];
        double x = lo[--nLo];
        int i = 0;
        for (;;) {
            int c = 2 * i + 1;
            if (c + 1 < nLo && lo[c + 1] > lo[c]) {
                c++;
            }
            if (c >= nLo || lo[c] <= x) {
                break;
            }
            lo[i] = lo[c];
            i = c;
        }
        lo[i] = x;
        return top;
    }

    private void pushHi(double x) {
        if (nHi == hi.length) {
            hi = java.util.Arrays.copyOf(hi, hi.length * 2);
        }
        int i = nHi++;
        while (i > 0) {
            int p = (i - 1) >>> 1;
            if (hi[p] <= x) {
                break;
            }
            hi[i] = hi[p];
            i = p;
        }
        hi[i] = x;
    }

    private double popHi() {
        double top = hi[0];
        double x = hi[--nHi];
        int i = 0;
        for (;;) {
            int c = 2 * i + 1;
            if (c + 1 < nHi && hi[c + 1] < hi[c]) {
                c++;
            }
            if (c >= nHi || hi[c] >= x) {
                break;
            }
            hi[i] = hi[c];
            i = c;
        }
        hi[i] = x;
        return top;
    }
}
