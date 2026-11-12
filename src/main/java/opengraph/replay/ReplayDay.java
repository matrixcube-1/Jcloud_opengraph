package opengraph.replay;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.TreeSet;
import opengraph.core.DataLoader;
import opengraph.core.Engine;
import opengraph.core.EvalResult;
import opengraph.core.ExecOutcome;
import opengraph.core.ExecParams;
import opengraph.core.PathTables;
import opengraph.core.Universe;

/**
 * Replay d'une journee de corpus — port exact de optuna_duka.py::replay_day
 * (memes constantes, meme ordre d'operations).
 *
 * Le corpus est charge dans un Engine via DataLoader ; dayData derive les
 * tableaux de timestamps par symbole directement des series de l'engine
 * (memes donnees que la lecture CSV du script, tri stable identique).
 */
public final class ReplayDay {

    private ReplayDay() {}

    /** Warm-up : ticks minimum par symbole avant la premiere eval. */
    public static final int WARMUP = 100;
    /** Convention Mo7 : instants echantillonnes (le live evalue a chaque
     * tick — la frequence detect est indicative seulement). */
    public static final long EVAL_EVERY_MS = 500;
    public static final long MIN_SEQ_GAP_MS = 2_000;
    public static final long MAX_HOLD_MS = 5_000;
    /** Latence colocalisee JForex/JCloud par defaut — la valeur d'une
     * campagne vient de Params.latMs (CLI --lat_ms) et doit etre
     * reportee dans chaque tableau de resultats. */
    public static final double LAT_MS = 5.0;

    /**
     * Donnees journalieres precomputees :
     * ev = timestamps uniques de tous les symboles (tri croissant) ;
     * symTs[e] = timestamps de l'arete e sur ce jour ;
     * seenMin[i] = min sur les symboles du nombre de ticks vus avant ev[i]
     * (np.searchsorted(ts, ev) minimum — miroir exact du script).
     */
    public static class DayData {
        public final long[] ev;
        public final long[][] symTs;
        public final long[] seenMin;

        DayData(long[] ev, long[][] symTs, long[] seenMin) {
            this.ev = ev;
            this.symTs = symTs;
            this.seenMin = seenMin;
        }
    }

    /**
     * Extrait les series du jour `day` depuis l'engine chargee
     * (filtre [epoch(day), epoch(day)+86 400 000)). Les ts des series sont
     * deja dedupliques par construction du chargement (fichiers non
     * recouvrants) — np.unique equivalent.
     */
    public static DayData dayData(Engine eng, String day) {
        long t0 = DataLoader.epochMs(day);
        long t1 = t0 + 86_400_000L;
        long[][] symTs = new long[Universe.FX.length][];
        TreeSet<Long> all = new TreeSet<>();
        for (int e = 0; e < Universe.FX.length; e++) {
            int si = eng.fxSi[e];
            List<Long> v = new ArrayList<>();
            if (si >= 0) {
                var s = eng.data.get(si);
                for (int k = 0; k < s.len(); k++) {
                    if (s.ts[k] >= t0 && s.ts[k] < t1) {
                        v.add(s.ts[k]);
                    }
                }
            }
            // dedup (ts peut se repeter — np.unique)
            TreeSet<Long> u = new TreeSet<>(v);
            symTs[e] = new long[u.size()];
            int i = 0;
            for (long t : u) {
                symTs[e][i++] = t;
                all.add(t);
            }
        }
        long[] ev = new long[all.size()];
        int i = 0;
        for (long t : all) {
            ev[i++] = t;
        }
        long[] seenMin = new long[ev.length];
        for (int k = 0; k < ev.length; k++) {
            long m = Long.MAX_VALUE;
            for (int e = 0; e < Universe.FX.length; e++) {
                m = Math.min(m, searchsorted(symTs[e], ev[k]));
            }
            seenMin[k] = m;
        }
        return new DayData(ev, symTs, seenMin);
    }

    /** np.searchsorted(ts, t) : nombre d'elements <= t-... — side left :
     * indice du premier element >= t = nombre d'elements < t. */
    private static long searchsorted(long[] ts, long t) {
        int lo = 0, hi = ts.length;
        while (lo < hi) {
            int mid = (lo + hi) >>> 1;
            if (ts[mid] < t) {
                lo = mid + 1;
            } else {
                hi = mid;
            }
        }
        return lo;
    }

    /**
     * Ensemble des aretes du panier du chemin pi : jambes + conversion
     * USD d'entree (s0 != USD) + conversion de sortie (end != USD).
     * Port de optuna_duka.basket_edges.
     */
    public static int[] basketEdges(PathTables pt, int pi) {
        TreeSet<Integer> es = new TreeSet<>();
        for (int li = 0; li < pt.pL[pi]; li++) {
            es.add(pt.pE[pi][li]);
        }
        int s0 = pt.pS[pi];
        int end = pt.pEnd[pi];
        for (int c : new int[] {s0, end}) {
            if (c != 0) {
                int e = Universe.usdEdge(c);
                if (e >= 0) {
                    es.add(e);
                }
            }
        }
        int[] out = new int[es.size()];
        int i = 0;
        for (int e : es) {
            out[i++] = e;
        }
        return out;
    }

    /** Agregat de la journee : {pnl, trades, aborts}. */
    public static double[] replayDay(Engine eng, PathTables pt, DayData dd,
                                     Engine.Params p) {
        long[] lockUntil = new long[Universe.FX.length];
        Arrays.fill(lockUntil, Long.MIN_VALUE);
        long tFree = Long.MIN_VALUE;
        long lastEval = Long.MIN_VALUE;
        double pnl = 0.0;
        int trades = 0;
        int aborts = 0;
        ExecParams ep = new ExecParams(p.latMs, p.tauSlipBp, p.qMin, false);

        for (int i = 0; i < dd.ev.length; i++) {
            long t = dd.ev[i];
            // t < lastEval + EVAL (pas de soustraction : MIN_VALUE overflow)
            if (dd.seenMin[i] < WARMUP || t < lastEval + EVAL_EVERY_MS) {
                continue;
            }
            lastEval = t;
            // sync explicite — evalAt lit l'etat publie (comme pybind
            // qui appelait eng.sync(t) avant eval_at en replay)
            eng.sync(t);
            EvalResult evr = eng.evalAt(t, p.freshMs, p.spreadCap,
                    p.commScale, pt);
            if (evr == null) {
                continue;
            }
            if (evr.netUsd < p.dddMin || t < tFree) {
                continue;
            }
            int[] bes = basketEdges(pt, evr.pathIdx);
            boolean locked = false;
            for (int x : bes) {
                if (lockUntil[x] > t) {
                    locked = true;
                    break;
                }
            }
            if (locked) {
                continue;
            }
            ExecOutcome r = eng.execSeq(t, evr.pathIdx, ep, pt);
            for (int x : bes) {
                long[] ts = dd.symTs[x];
                int j = Arrays.binarySearch(ts, t);
                // next index > t (searchsorted right)
                j = j >= 0 ? j + 1 : ~j;
                // side="right" : premier index avec ts[j] > t
                while (j < ts.length && ts[j] <= t) {
                    j++;
                }
                lockUntil[x] = j < ts.length ? ts[j] : Long.MAX_VALUE;
            }
            tFree = t + MIN_SEQ_GAP_MS + MAX_HOLD_MS;
            if (r == null) {
                continue;
            }
            trades += 1;
            pnl += r.pnl();
            if (r instanceof ExecOutcome.Abort) {
                aborts += 1;
            }
        }
        return new double[] {pnl, trades, aborts};
    }
}
