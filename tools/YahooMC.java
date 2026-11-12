import opengraph.core.*;
import java.io.*;
import java.nio.file.*;
import java.util.*;

/**
 * MC sur donnees externes Yahoo (mids 1 min alignes, 28 paires).
 * Charge chaque SYM.csv (ts_ms,mid), construit bid/ask = mid +/- spr*mid/2,
 * puis rejoue evalAt a chaque timestamp commun.
 * Mode "zero" : spread 0 + comm 0 -> borne superieure d'incoherence des mids.
 * Mode "real" : spreads medians corpus + commissions -> executable.
 */
public final class YahooMC {

    static final Map<String, Double> SPR = Map.ofEntries(
        Map.entry("EURUSD", 1.79e-05), Map.entry("GBPUSD", 3.74e-05),
        Map.entry("USDJPY", 1.91e-05), Map.entry("USDCHF", 9.71e-05),
        Map.entry("AUDUSD", 1.14e-04), Map.entry("USDCAD", 7.84e-05),
        Map.entry("NZDUSD", 1.59e-04), Map.entry("EURGBP", 8.35e-05),
        Map.entry("EURJPY", 5.11e-05), Map.entry("EURCHF", 9.76e-05),
        Map.entry("EURAUD", 1.13e-04), Map.entry("EURCAD", 9.55e-05),
        Map.entry("EURNZD", 1.06e-04), Map.entry("GBPJPY", 7.13e-05),
        Map.entry("GBPCHF", 1.18e-04), Map.entry("GBPAUD", 7.87e-05),
        Map.entry("GBPCAD", 1.23e-04), Map.entry("GBPNZD", 2.08e-04),
        Map.entry("AUDJPY", 4.54e-05), Map.entry("AUDCHF", 2.08e-04),
        Map.entry("AUDCAD", 1.73e-04), Map.entry("AUDNZD", 1.38e-04),
        Map.entry("CADJPY", 8.03e-05), Map.entry("CADCHF", 2.73e-04),
        Map.entry("CHFJPY", 9.96e-05), Map.entry("NZDJPY", 7.85e-05),
        Map.entry("NZDCAD", 2.89e-04), Map.entry("NZDCHF", 3.64e-04));

    static SymbolSeries load(Path dir, String sym, double sprFrac) throws IOException {
        List<Long> ts = new ArrayList<>();
        List<Double> mid = new ArrayList<>();
        try (BufferedReader r = Files.newBufferedReader(dir.resolve(sym + ".csv"))) {
            r.readLine();
            String line;
            while ((line = r.readLine()) != null) {
                String[] c = line.split(",");
                ts.add(Long.parseLong(c[0]));
                mid.add(Double.parseDouble(c[1]));
            }
        }
        int n = ts.size();
        long[] t = new long[n];
        double[] bid = new double[n], ask = new double[n];
        double[] vol = new double[n];
        for (int i = 0; i < n; i++) {
            t[i] = ts.get(i);
            double h = mid.get(i) * sprFrac * 0.5;
            bid[i] = mid.get(i) - h;
            ask[i] = mid.get(i) + h;
            vol[i] = 1e6;
        }
        return new SymbolSeries(sym, t, bid, ask, vol, vol);
    }

    static void run(Path dir, boolean zeroCost, double commScale,
                    double dddMin, PathTables pt) throws IOException {
        List<SymbolSeries> series = new ArrayList<>();
        for (String s : Universe.FX) {
            double spr = zeroCost ? 0.0 : SPR.get(s);
            Path f = dir.resolve(s + ".csv");
            if (Files.isRegularFile(f)) {
                series.add(load(dir, s, spr));
            }
        }
        Engine e = new Engine(series);
        // timestamps communs : intersection des series
        Set<Long> common = null;
        for (SymbolSeries s : series) {
            Set<Long> st = new HashSet<>();
            for (long t : s.ts) st.add(t);
            common = common == null ? st : intersect(common, st);
        }
        List<Long> times = new ArrayList<>(common);
        Collections.sort(times);

        int evals = 0, detect = 0;
        double maxNet = Double.NEGATIVE_INFINITY, sumNet = 0;
        int bestPi = -1;
        long bestT = 0;
        for (long t : times) {
            e.sync(t);
            EvalResult r = e.evalAt(t, 8000, 1.76, commScale, pt);
            if (r == null) continue;
            evals++;
            sumNet += r.netUsd;
            if (r.netUsd > maxNet) { maxNet = r.netUsd; bestPi = r.pathIdx; bestT = t; }
            if (r.netUsd >= dddMin) detect++;
        }
        System.out.printf("  evals=%d detections(net>=%.2f)=%d maxNet=%.4f meanNet=%.4f%n",
                evals, dddMin, detect, maxNet,
                evals > 0 ? sumNet / evals : Double.NaN);
        if (bestPi >= 0) {
            StringBuilder sb = new StringBuilder();
            sb.append("s0=").append(Universe.CURR[pt.pS[bestPi]]);
            for (int li = 0; li < pt.pL[bestPi]; li++) {
                sb.append(" ->").append(Universe.FX[pt.pE[bestPi][li]])
                  .append(pt.pDir[bestPi][li] == 1 ? "+" : "-");
            }
            sb.append(" end=").append(Universe.CURR[pt.pEnd[bestPi]]);
            System.out.println("  argmax @t=" + new java.util.Date(bestT)
                    + " : " + sb);
        }
    }

    static Set<Long> intersect(Set<Long> a, Set<Long> b) {
        Set<Long> out = new HashSet<>();
        for (Long x : a) if (b.contains(x)) out.add(x);
        return out;
    }

    public static void main(String[] a) throws Exception {
        Path dir = java.nio.file.Paths.get(a[0]);
        PathTables pt = PathTables.build();
        System.out.println("[ZERO-COST] spread=0 comm=0 — borne max incoherence mids :");
        run(dir, true, 0.0, 0.27, pt);
        System.out.println("[REAL] spreads corpus + comm 4$/100K :");
        run(dir, false, 1.0, 0.27, pt);
        System.out.println("[REAL-LOWFEE] spreads corpus + comm 1$/100K :");
        run(dir, false, 0.25, 0.27, pt);
    }
}
