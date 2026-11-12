package opengraph.replay;

import opengraph.core.BoutUniverse;
import opengraph.core.DataLoader;
import opengraph.core.Engine;
import opengraph.core.EvalResult;
import opengraph.core.ExecOutcome;
import opengraph.core.ExecParams;
import opengraph.core.ExtPathTables;
import opengraph.core.PathTables;
import opengraph.core.Universe;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Locale;
import java.util.Random;

/**
 * SessionMC — mesure quant par fenêtre de session sur corpus réel.
 *
 * Replay tick-par-tick des jours in-sample en bucketant chaque éval
 * (EVAL_EVERY_MS) dans sa fenêtre horaire UTC, puis Monte Carlo
 * bootstrap : IC sur le taux de détection et le PnL exec par session.
 *
 * Le live tourne à minuit (session morte) — ceci projette ce que la
 * stratégie verra sur les heures liquides NY/London, sur données
 * réelles, sous Convention A stricte.
 *
 * Fenêtres UTC : ASIA 00-07 · LONDON 07-12 · OVERLAP 12-16 · NY 16-21
 *                LATE 21-24.
 *
 * Usage : SessionMC --root C:\arb_duka\data
 *         --days 2026-10-22,2026-10-23,2026-10-26 [--reps 2000]
 */
public final class SessionMC {

    private static final long H = 3_600_000L;

    private static final class Win {
        final String name; final int fromH, toH;
        int evals, detects, trades, aborts;
        double pnl, sumNet, maxNet = Double.NEGATIVE_INFINITY;
        final List<Double> execPnls = new ArrayList<>();

        Win(String name, int fromH, int toH) {
            this.name = name; this.fromH = fromH; this.toH = toH;
        }
        boolean has(long dayStart, long t) {
            long off = t - dayStart;
            return off >= fromH * H && off < toH * H;
        }
    }

    public static void main(String[] args) throws Exception {
        Locale.setDefault(Locale.US);
        Path root = DataLoader.defaultRoot();
        String[] days = {"2026-10-22", "2026-10-23", "2026-10-26"};
        int reps = 2000; long seed = 42; int maxLegs = 3;
        boolean ext = false;
        double dddMin = 0.27, latMs = 60.0, commScale = 1.10;
        double jitterUs = 10.0;
        for (int i = 0; i < args.length; i++) {
            switch (args[i]) {
                case "--root": root = Path.of(args[++i]); break;
                case "--days": days = args[++i].split(","); break;
                case "--reps": reps = Integer.parseInt(args[++i]); break;
                case "--seed": seed = Long.parseLong(args[++i]); break;
                case "--ddd-min": dddMin = Double.parseDouble(args[++i]); break;
                case "--comm-scale": commScale = Double.parseDouble(args[++i]); break;
                case "--lat_ms": latMs = Double.parseDouble(args[++i]); break;
                case "--jitter-us": jitterUs = Double.parseDouble(args[++i]); break;
                case "--max-legs": maxLegs = Integer.parseInt(args[++i]); break;
                case "--ext": ext = true; break;
                default: break;
            }
        }

        Engine.Params p = Engine.Params.docDefaults();
        p.freshMs = 8000; p.spreadCap = 1.76; p.commScale = commScale;
        p.dddMin = dddMin; p.latMs = latMs; p.tauSlipBp = 0.69;
        p.qMin = 0.5;

        PathTables pt = PathTables.build();
        if (maxLegs < 3) {
            pt = PathTables.maxLegs(pt, maxLegs);
        }
        ExtPathTables xt = ext ? ExtPathTables.build(pt) : null;
        System.out.printf(Locale.US,
                "chemins evalues: %d (max %d jambes)%s%n", pt.len(), maxLegs,
                ext ? " + " + xt.len() + " etendus bouts" : "");
        int bestXi = -1; long bestXiT = 0; double bestXiNet = Double.NEGATIVE_INFINITY;
        Win[] wins = {new Win("ASIA   00-07", 0, 7),
                      new Win("LONDON 07-12", 7, 12),
                      new Win("OVERLAP 12-16", 12, 16),
                      new Win("NY     16-21", 16, 21),
                      new Win("LATE   21-24", 21, 24)};

        for (String day : days) {
            System.out.println("=== " + day + " ===");
            Engine eng = ext
                    ? DataLoader.loadFullEngine(root, new String[]{day})
                    : DataLoader.loadFxEngine(root, new String[]{day});
            ReplayDay.DayData dd = ReplayDay.dayData(eng, day);
            long dayStart = DataLoader.epochMs(day);
            for (Win w : wins) {
                w.evals = w.detects = w.trades = w.aborts = 0;
                w.pnl = w.sumNet = 0; w.maxNet = Double.NEGATIVE_INFINITY;
                w.execPnls.clear();
            }

            long[] lockUntil = new long[Universe.FX.length];
            Arrays.fill(lockUntil, Long.MIN_VALUE);
            long tFree = Long.MIN_VALUE, lastEval = Long.MIN_VALUE;
            ExecParams ep = new ExecParams(p.latMs, jitterUs / 1000.0,
                    p.tauSlipBp, p.qMin, false,
                    Double.POSITIVE_INFINITY, Double.POSITIVE_INFINITY,
                    Double.NEGATIVE_INFINITY, 0, false);

            for (int i = 0; i < dd.ev.length; i++) {
                long t = dd.ev[i];
                if (dd.seenMin[i] < ReplayDay.WARMUP
                        || t < lastEval + ReplayDay.EVAL_EVERY_MS) {
                    continue;
                }
                lastEval = t;
                eng.sync(t);
                EvalResult evr = ext
                        ? eng.evalBoutAt(t, p.freshMs, p.spreadCap,
                                p.commScale, pt, xt)
                        : eng.evalAt(t, p.freshMs, p.spreadCap,
                                p.commScale, pt);
                if (evr == null) {
                    continue;
                }
                if (ext && evr.netUsd > bestXiNet) {
                    bestXiNet = evr.netUsd; bestXi = evr.pathIdx; bestXiT = t;
                }
                for (Win w : wins) {
                    if (w.has(dayStart, t)) {
                        w.evals++;
                        w.sumNet += evr.netUsd;
                        if (evr.netUsd > w.maxNet) {
                            w.maxNet = evr.netUsd;
                        }
                        if (evr.netUsd >= p.dddMin && t >= tFree) {
                            if (ext) {
                                // execSeq n'execute que des prefixe FX —
                                // detection-only pour les chemins etendus
                                w.detects++;
                                continue;
                            }
                            int[] bes = ReplayDay.basketEdges(pt,
                                    evr.pathIdx);
                            boolean locked = false;
                            for (int x : bes) {
                                if (lockUntil[x] > t) {
                                    locked = true; break;
                                }
                            }
                            if (!locked) {
                                w.detects++;
                                ExecOutcome r = eng.execSeq(t,
                                        evr.pathIdx, ep, pt);
                                for (int x : bes) {
                                    long[] ts = dd.symTs[x];
                                    int j = Arrays.binarySearch(ts, t);
                                    j = j >= 0 ? j + 1 : ~j;
                                    while (j < ts.length && ts[j] <= t) {
                                        j++;
                                    }
                                    lockUntil[x] = j < ts.length
                                            ? ts[j] : Long.MAX_VALUE;
                                }
                                tFree = t + ReplayDay.MIN_SEQ_GAP_MS
                                        + ReplayDay.MAX_HOLD_MS;
                                if (r != null) {
                                    w.trades++;
                                    w.pnl += r.pnl();
                                    w.execPnls.add(r.pnl());
                                    if (r instanceof ExecOutcome.Abort) {
                                        w.aborts++;
                                    }
                                }
                            }
                        }
                    }
                }
            }
            for (Win w : wins) {
                System.out.printf(Locale.US,
                        "%s evals=%6d detect=%3d exec=%3d aborts=%3d"
                        + " maxNet=%8.4f meanNet=%8.4f execPnl=%9.2f%n",
                        w.name, w.evals, w.detects, w.trades, w.aborts,
                        w.evals > 0 ? w.maxNet : Double.NaN,
                        w.evals > 0 ? w.sumNet / w.evals : Double.NaN,
                        w.pnl);
            }
            pool(wins);
        }

        if (ext && bestXi >= 0) {
            int fpi = xt.fxPi[bestXi];
            StringBuilder sb = new StringBuilder();
            sb.append("s0=").append(Universe.CURR[pt.pS[fpi]]);
            for (int li = 0; li < pt.pL[fpi]; li++) {
                sb.append(" ->").append(Universe.FX[pt.pE[fpi][li]])
                  .append(pt.pDir[fpi][li] == 1 ? "+" : "-");
            }
            sb.append(" => ").append(BoutUniverse.BOUTS[xt.bout[bestXi]].name);
            System.out.printf(Locale.US,
                    "\nargmax etendu @%tF %tT : %s (net=%.4f)%n",
                    bestXiT, bestXiT, sb, bestXiNet);
        }

        // ---------- Monte Carlo bootstrap ----------
        System.out.println("\n=== MC bootstrap x" + reps
                + " (toutes fenêtres, 3 jours) ===");
        Random rng = new Random(seed);
        for (Win w : wins) {
            if (w.execPnls.isEmpty()) {
                System.out.printf(Locale.US,
                        "%s : 0 trade exec — pas de PnL à borner%n", w.name);
                continue;
            }
            double[] mean = new double[reps];
            double[] tot = new double[reps];
            int n = w.execPnls.size();
            for (int r = 0; r < reps; r++) {
                double m = 0, s = 0;
                for (int k = 0; k < n; k++) {
                    double v = w.execPnls.get(
                            rng.nextInt(n));
                    m += v; s += v;
                }
                mean[r] = m / n; tot[r] = s;
            }
            Arrays.sort(mean); Arrays.sort(tot);
            System.out.printf(Locale.US,
                    "%s n=%d | pnl/trade IC95 [%.2f .. %.2f] med %.2f"
                    + " | total IC95 [%.2f .. %.2f]%n",
                    w.name, n,
                    mean[(int) (0.025 * reps)],
                    mean[(int) (0.975 * reps)],
                    mean[reps / 2],
                    tot[(int) (0.025 * reps)],
                    tot[(int) (0.975 * reps)]);
        }
    }

    private static final List<Win> POOL = new ArrayList<>();

    private static void pool(Win[] wins) {
        // cumul multi-jours : la fenetre i fusionne ses stats
        if (POOL.isEmpty()) {
            for (Win w : wins) {
                Win c = new Win(w.name, w.fromH, w.toH);
                POOL.add(c);
            }
        }
        for (int i = 0; i < wins.length; i++) {
            Win c = POOL.get(i), w = wins[i];
            c.evals += w.evals; c.detects += w.detects;
            c.trades += w.trades; c.aborts += w.aborts;
            c.pnl += w.pnl; c.sumNet += w.sumNet;
            c.maxNet = Math.max(c.maxNet, w.maxNet);
            c.execPnls.addAll(w.execPnls);
        }
    }
}
