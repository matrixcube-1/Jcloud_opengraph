package opengraph.replay;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import opengraph.core.DataLoader;
import opengraph.core.Engine;
import opengraph.core.PathTables;

/**
 * Calibrage pur Java — remplace le driver Optuna Python.
 *
 * Usage :
 *   CalibCli --root C:\arb_duka --days 2026-10-22,2026-10-23
 *            [--fresh-ms 8000] [--spread-cap 1.76] [--comm-scale 1.10]
 *            [--ddd-min 0.27] [--tau-slip-bp 0.69] [--q-min 0.5]
 *            [--sweep dddMin=0.1,0.2,0.3 ...]   (repetable)
 *            [--holdout 2026-10-27]
 *            [--trials N --threads T --seed S --lat_ms X]
 *            [--ext] [--refine-top K --refine-n M]
 *
 * --trials N : random search miroir Optuna v5 (espace scelle).
 * --ext      : elargit l'espace avec les 2 dimensions du cycle
 *              detection->fill mesurees sur venue : entryDelayMs
 *              log[0,1000] (execution retardee — separe l'edge reel
 *              de l'artefact staleness) et persistMinMs [0,2000]
 *              (le meme argmax doit persister avant d'executer).
 * --refine   : phase 2 quasi-TPE — K meilleurs faisables x M
 *              voisins gaussiens (sigma=10% plage, bornes clampees).
 * Rapports : pnl total, trades, aborts + pire_jour (robustesse
 * multi-jours — un seul jour profitable ne suffit pas).
 *
 * Sans --sweep : evalue le jeu de parametres fourni, sort
 *   pnl=X.XX trades=N aborts=M abortRate=R
 * Avec --sweep : grille cartesienne deterministe sur les cles citees,
 * chaque combinaison est evaluee sur TOUS les jours in-sample ; le
 * meilleur pnl moyen/jour est affiche puis evalue UNE fois sur
 * --holdout s'il est fourni.
 *
 * Latence par jambe : defaut ReplayDay.LAT_MS=5.0 (colocalisation
 * JCloud), surchargeable via --lat_ms (a reporter dans chaque resultat).
 */
public final class CalibCli {

    private CalibCli() {}

    private static void setParam(Engine.Params p, String key, double v) {
        switch (key) {
            case "freshMs": case "fresh_ms":
                p.freshMs = v; break;
            case "spreadCap": case "spread_cap":
                p.spreadCap = v; break;
            case "commScale": case "comm_scale":
                p.commScale = v; break;
            case "dddMin": case "ddd_min":
                p.dddMin = v; break;
            case "tauSlipBp": case "tau_slip_bp":
                p.tauSlipBp = v; break;
            case "qMin": case "q_min":
                p.qMin = v; break;
            case "latMs": case "lat_ms":
                p.latMs = v; break;
            case "entryDelay": case "entry_delay_ms":
                p.entryDelayMs = v; break;
            case "persistMin": case "persist_min_ms":
                p.persistMinMs = v; break;
            default:
                throw new IllegalArgumentException("param inconnu: " + key);
        }
    }

    public static void main(String[] args) throws Exception {
        Path root = DataLoader.defaultRoot();
        String[] days = DataLoader.DAYS.clone();
        String holdout = null;
        Engine.Params base = new Engine.Params(
                8_000.0, 1.76, 1.10, 0.27, ReplayDay.LAT_MS,
                0.69, 0.5, 0.0, false);
        Map<String, double[]> sweeps = new TreeMap<>();
        int trials = 0;
        int threads = 1;
        long seed = 42L;
        boolean ext = false;
        int refineTop = 0;
        int refineN = 0;

        for (int i = 0; i < args.length; i++) {
            String a = args[i];
            switch (a) {
                case "--root": root = Path.of(args[++i]); break;
                case "--days": days = args[++i].split(","); break;
                case "--holdout": holdout = args[++i]; break;
                case "--ext": ext = true; break;
                case "--refine-top": refineTop = Integer.parseInt(args[++i]); break;
                case "--refine-n": refineN = Integer.parseInt(args[++i]); break;
                case "--trials": trials = Integer.parseInt(args[++i]); break;
                case "--threads":
                    threads = Integer.parseInt(args[++i]); break;
                case "--seed": seed = Long.parseLong(args[++i]); break;
                case "--sweep":
                    String kv = args[++i];
                    int eq = kv.indexOf('=');
                    String key = kv.substring(0, eq);
                    String[] vs = kv.substring(eq + 1).split(",");
                    double[] vals = new double[vs.length];
                    for (int k = 0; k < vs.length; k++) {
                        vals[k] = Double.parseDouble(vs[k]);
                    }
                    sweeps.put(key, vals);
                    break;
                default:
                    if (a.startsWith("--")) {
                        setParam(base, a.substring(2)
                                .replace('-', '_'),
                                Double.parseDouble(args[++i]));
                    } else {
                        throw new IllegalArgumentException("arg: " + a);
                    }
            }
        }

        if (trials > 0) {
            randomSearch(root, days, holdout, trials, threads, seed,
                    base.latMs, ext, refineTop, refineN);
            return;
        }

        System.err.printf("Chargement corpus %s, %d jours...%n",
                root, days.length);
        long t0 = System.currentTimeMillis();
        Engine eng = DataLoader.loadFxEngine(root, days);
        PathTables pt = PathTables.build();
        ReplayDay.DayData[] dds = new ReplayDay.DayData[days.length];
        long evTotal = 0;
        for (int i = 0; i < days.length; i++) {
            dds[i] = ReplayDay.dayData(eng, days[i]);
            evTotal += dds[i].ev.length;
        }
        System.err.printf("Pret : %,d evenements en %.1f s%n",
                evTotal, (System.currentTimeMillis() - t0) / 1e3);

        List<String> skeys = new ArrayList<>(sweeps.keySet());
        if (skeys.isEmpty()) {
            double[] r = evalAll(eng, pt, dds, base);
            System.out.printf("pnl=%.2f trades=%d aborts=%d abortRate=%.3f"
                            + " lat=%.1f%n",
                    r[0], (int) r[1], (int) r[2],
                    r[1] > 0 ? r[2] / r[1] : 0.0, base.latMs);
            return;
        }

        // Grille cartesienne deterministe
        String bestDesc = null;
        Engine.Params best = null;
        double[] bestR = new double[] {Double.NEGATIVE_INFINITY, 0, 0};
        int nComb = 1;
        for (String k : skeys) {
            nComb *= sweeps.get(k).length;
        }
        System.err.printf("Grille : %d combinaisons x %d jours%n",
                nComb, dds.length);
        int done = 0;
        List<int[]> grid = new ArrayList<>();
        expand(skeys, sweeps, 0, new int[skeys.size()], grid);
        for (int[] comb : grid) {
            Engine.Params p = copy(base);
            StringBuilder sb = new StringBuilder();
            for (int k = 0; k < skeys.size(); k++) {
                double v = sweeps.get(skeys.get(k))[comb[k]];
                setParam(p, skeys.get(k), v);
                sb.append(sb.length() == 0 ? "" : " ")
                        .append(skeys.get(k)).append('=').append(v);
            }
            double[] r = evalAll(eng, pt, dds, p);
            System.out.printf("[%4d/%d] %-60s pnl=%8.2f trades=%3.0f aborts=%3.0f%n",
                    ++done, nComb, sb, r[0], r[1], r[2]);
            if (r[0] > bestR[0]) {
                bestR = r;
                best = p;
                bestDesc = sb.toString();
            }
        }
        System.out.printf("BEST %s -> pnl=%.2f trades=%.0f aborts=%.0f"
                        + " lat=%.1f%n",
                bestDesc, bestR[0], bestR[1], bestR[2], best.latMs);

        if (holdout != null && best != null) {
            // Engine combine + cutoff (MED_CUTOFF du POC Python) : la mediane du cap
            // spread_cap x med vient de l'in-sample seul (sinon la
            // journee holdout fuite dans la gate).
            String[] all = new String[days.length + 1];
            System.arraycopy(days, 0, all, 0, days.length);
            all[days.length] = holdout;
            Engine heng = DataLoader.loadFxEngine(root, all);
            heng.setMedUntil(DataLoader.epochMs(holdout));
            ReplayDay.DayData hd = ReplayDay.dayData(heng, holdout);
            double[] r = ReplayDay.replayDay(heng, pt, hd, best);
            System.out.printf("HOLDOUT %s pnl=%.2f trades=%.0f aborts=%.0f"
                            + " lat=%.1f%n",
                    holdout, r[0], r[1], r[2], best.latMs);
        }
    }

    // ---------------- random search (miroir optuna v5) ----------------

    /**
     * Espace de recherche Optuna v5, echantillonne uniformement (seed
     * fixe -> deterministe) : fresh_ms log[2000,30000], spread_cap
     * [1,3], comm_scale [1,1.3], ddd_min [0,1], tau_slip_bp [1,4],
     * q_min [0.3,0.9]. Contraintes : trades>=10 et abort_rate<=30 %.
     */
    private static Engine.Params sample(java.util.Random rng,
                                        double latMs, boolean ext) {
        Engine.Params p = new Engine.Params(0, 0, 0, 0, latMs,
                0, 0, 0.0, false);
        p.freshMs = Math.exp(Math.log(2_000.0) + rng.nextDouble()
                * (Math.log(30_000.0) - Math.log(2_000.0)));
        p.spreadCap = 1.0 + 2.0 * rng.nextDouble();
        p.commScale = 1.0 + 0.3 * rng.nextDouble();
        p.dddMin = rng.nextDouble();
        p.tauSlipBp = 1.0 + 3.0 * rng.nextDouble();
        p.qMin = 0.3 + 0.6 * rng.nextDouble();
        if (ext) {
            // EXT : delai d'entree log[0,1000] ms + persistance min du
            // signal [0,2000] ms — les deux dimensions qui separent
            // l'edge reel de l'artefact de staleness mesure sur venue.
            p.entryDelayMs = Math.exp(Math.log(1.0) + rng.nextDouble()
                    * (Math.log(1_000.0) - Math.log(1.0))) - 1.0;
            if (p.entryDelayMs < 0.5) {
                p.entryDelayMs = 0.0;
            }
            p.persistMinMs = 2_000.0 * rng.nextDouble();
        }
        return p;
    }

    private static String desc(Engine.Params p) {
        String s = String.format(
                "fresh=%.0f cap=%.2f comm=%.2f ddd=%.3f tau=%.2f qmin=%.2f"
                        + " lat=%.1f",
                p.freshMs, p.spreadCap, p.commScale, p.dddMin,
                p.tauSlipBp, p.qMin, p.latMs);
        if (p.entryDelayMs > 0 || p.persistMinMs > 0) {
            s += String.format(" dl=%.0f ps=%.0f",
                    p.entryDelayMs, p.persistMinMs);
        }
        return s;
    }

    private static class TrialRec {
        final Engine.Params p;
        final double pnl, trades, aborts, worstDay;
        final boolean feasible;

        TrialRec(Engine.Params p, double[] r) {
            this.p = p;
            this.pnl = r[0];
            this.trades = r[1];
            this.aborts = r[2];
            this.worstDay = r.length > 3 ? r[3] : Double.NaN;
            this.feasible = r[1] >= 10
                    && (r[1] <= 0 || r[2] / r[1] <= 0.30);
        }
    }

    /**
     * Recherche aleatoire multi-thread : chaque thread charge son propre
     * Engine (etat mutable non partageable) et rejoue sa part d'essais.
     */
    private static void randomSearch(Path root, String[] days,
                                     String holdout, int trials,
                                     int threads, long seed, double latMs,
                                     boolean ext, int refineTop,
                                     int refineN)
            throws Exception {
        System.err.printf("Random search : %d essais, %d threads, "
                + "seed=%d, lat=%.1f ms, %d jours in-sample%s%n",
                trials, threads, seed, latMs, days.length,
                ext ? " [EXT: entryDelay+persistMin]" : "");
        List<TrialRec> all = java.util.Collections.synchronizedList(
                new ArrayList<>());
        Thread[] ts = new Thread[threads];
        for (int w = 0; w < threads; w++) {
            final int wid = w;
            ts[w] = new Thread(() -> {
                try {
                    Engine eng = DataLoader.loadFxEngine(root, days);
                    PathTables pt = PathTables.build();
                    ReplayDay.DayData[] dds =
                            new ReplayDay.DayData[days.length];
                    for (int i = 0; i < days.length; i++) {
                        dds[i] = ReplayDay.dayData(eng, days[i]);
                    }
                    java.util.Random rng = new java.util.Random(seed);
                    // skip deterministe pour que les essais soient
                    // identiques quel que soit le nombre de threads
                    for (int i = 0; i < trials; i++) {
                        Engine.Params p = sample(rng, latMs, ext);
                        if (i % threads != wid) {
                            continue;
                        }
                        double[] r = evalAll(eng, pt, dds, p);
                        TrialRec rec = new TrialRec(copy(p), r);
                        all.add(rec);
                        System.out.printf(
                                "[%d] %s pnl=%9.2f trades=%3.0f "
                                        + "aborts=%3.0f %s%n",
                                i, desc(p), r[0], r[1], r[2],
                                rec.feasible ? "FEAS" : "    ");
                    }
                } catch (Exception ex) {
                    throw new RuntimeException(ex);
                }
            }, "calib-" + w);
            ts[w].start();
        }
        for (Thread t : ts) {
            t.join();
        }

        List<TrialRec> feas = new ArrayList<>();
        for (TrialRec r : all) {
            if (r.feasible) {
                feas.add(r);
            }
        }
        feas.sort((a, b) -> Double.compare(b.pnl, a.pnl));
        List<TrialRec> top = new ArrayList<>(all);
        top.sort((a, b) -> Double.compare(b.pnl, a.pnl));

        System.out.printf("%n=== %d essais termines, %d faisables ===%n",
                all.size(), feas.size());
        System.out.println("TOP-10 (pnl brut, toutes contraintes) :");
        for (int i = 0; i < Math.min(10, top.size()); i++) {
            TrialRec r = top.get(i);
            System.out.printf("  #%d %s pnl=%.2f trades=%.0f "
                    + "aborts=%.0f%n", i + 1, desc(r.p), r.pnl,
                    r.trades, r.aborts);
        }
        if (!feas.isEmpty()) {
            System.out.println("MEILLEUR FAISABLE (trades>=10, abort<=30%) :");
            TrialRec b = feas.get(0);
            System.out.printf("  %s pnl=%.2f trades=%.0f aborts=%.0f "
                    + "pire_jour=%.2f%n",
                    desc(b.p), b.pnl, b.trades, b.aborts, b.worstDay);
            if (refineTop > 0 && refineN > 0) {
                refine(root, days, holdout, feas, refineTop, refineN,
                        seed + 1, ext);
            }
            if (holdout != null) {
                // Engine combine in-sample + holdout, mediane figee a la
                // frontiere (MED_CUTOFF du POC) — un engine holdout seul
                // apprendrait la mediane du jour evalue (lookahead).
                String[] withHo = new String[days.length + 1];
                System.arraycopy(days, 0, withHo, 0, days.length);
                withHo[days.length] = holdout;
                Engine eng = DataLoader.loadFxEngine(root, withHo);
                eng.setMedUntil(DataLoader.epochMs(holdout));
                PathTables pt = PathTables.build();
                ReplayDay.DayData hd = ReplayDay.dayData(eng, holdout);
                double[] r = ReplayDay.replayDay(eng, pt, hd, b.p);
                System.out.printf("HOLDOUT %s pnl=%.2f trades=%.0f "
                        + "aborts=%.0f%n", holdout, r[0], r[1], r[2]);
            }
        } else {
            System.out.println("AUCUN ESSAI FAISABLE — la region "
                    + "contrainte est vide sur cet espace.");
        }
    }

    /**
     * Phase 2 (quasi-TPE) : pour chacun des K meilleurs essais faisables,
     * N tirages gaussiens sigma=10% de la plage autour du point, bornes
     * clampees sur l'espace v5 (+ ext). Deterministe via seed+1.
     */
    private static void refine(Path root, String[] days, String holdout,
                               List<TrialRec> feas, int topK, int nEach,
                               long seed, boolean ext) throws Exception {
        Engine eng = DataLoader.loadFxEngine(root, days);
        PathTables pt = PathTables.build();
        ReplayDay.DayData[] dds = new ReplayDay.DayData[days.length];
        for (int i = 0; i < days.length; i++) {
            dds[i] = ReplayDay.dayData(eng, days[i]);
        }
        java.util.Random rng = new java.util.Random(seed);
        TrialRec best = feas.get(0);
        System.out.printf("%n=== REFINE top-%d x %d ===%n", topK, nEach);
        for (int k = 0; k < Math.min(topK, feas.size()); k++) {
            Engine.Params c = feas.get(k).p;
            for (int j = 0; j < nEach; j++) {
                Engine.Params p = jitter(rng, c, ext);
                double[] r = evalAll(eng, pt, dds, p);
                TrialRec rec = new TrialRec(copy(p), r);
                if (rec.feasible && rec.pnl > best.pnl) {
                    best = rec;
                    System.out.printf("  -> nouveau best %s pnl=%.2f "
                            + "trades=%.0f aborts=%.0f pire_jour=%.2f%n",
                            desc(p), r[0], r[1], r[2], rec.worstDay);
                }
            }
        }
        System.out.printf("MEILLEUR POST-REFINE : %s pnl=%.2f "
                + "trades=%.0f aborts=%.0f pire_jour=%.2f%n",
                desc(best.p), best.pnl, best.trades, best.aborts,
                best.worstDay);
    }

    /** Voisinage gaussien sigma=10% de plage, borne sur l'espace v5. */
    private static Engine.Params jitter(java.util.Random rng,
                                        Engine.Params c, boolean ext) {
        Engine.Params p = copy(c);
        p.freshMs = clamp(c.freshMs * Math.exp(0.10 * rng.nextGaussian()
                * Math.log(15.0)), 2_000.0, 30_000.0);
        p.spreadCap = clamp(c.spreadCap + 0.10 * 2.0
                * rng.nextGaussian(), 1.0, 3.0);
        p.commScale = clamp(c.commScale + 0.10 * 0.3
                * rng.nextGaussian(), 1.0, 1.3);
        p.dddMin = clamp(c.dddMin + 0.10 * rng.nextGaussian(), 0.0, 1.0);
        p.tauSlipBp = clamp(c.tauSlipBp + 0.10 * 3.0
                * rng.nextGaussian(), 1.0, 4.0);
        p.qMin = clamp(c.qMin + 0.10 * 0.6 * rng.nextGaussian(),
                0.3, 0.9);
        if (ext) {
            p.entryDelayMs = clamp(c.entryDelayMs + 0.10 * 1_000.0
                    * rng.nextGaussian(), 0.0, 1_000.0);
            p.persistMinMs = clamp(c.persistMinMs + 0.10 * 2_000.0
                    * rng.nextGaussian(), 0.0, 2_000.0);
        }
        return p;
    }

    private static double clamp(double v, double lo, double hi) {
        return Math.max(lo, Math.min(hi, v));
    }

    private static void expand(List<String> keys,
                               Map<String, double[]> sweeps,
                               int i, int[] cur, List<int[]> out) {
        if (i == keys.size()) {
            out.add(cur.clone());
            return;
        }
        for (int k = 0; k < sweeps.get(keys.get(i)).length; k++) {
            cur[i] = k;
            expand(keys, sweeps, i + 1, cur, out);
        }
    }

    private static Engine.Params copy(Engine.Params p) {
        Engine.Params c = new Engine.Params(p.freshMs, p.spreadCap,
                p.commScale, p.dddMin, p.latMs, p.tauSlipBp, p.qMin,
                p.cooldownS, p.volGuard);
        c.entryDelayMs = p.entryDelayMs;
        c.persistMinMs = p.persistMinMs;
        return c;
    }

    /** -> {pnl_total, trades, aborts, pire_jour}. */
    private static double[] evalAll(Engine eng, PathTables pt,
                                    ReplayDay.DayData[] dds,
                                    Engine.Params p) {
        double pnl = 0, trades = 0, aborts = 0;
        double worst = Double.POSITIVE_INFINITY;
        for (ReplayDay.DayData dd : dds) {
            double[] r = ReplayDay.replayDay(eng, pt, dd, p);
            pnl += r[0];
            trades += r[1];
            aborts += r[2];
            worst = Math.min(worst, r[0]);
        }
        return new double[] {pnl, trades, aborts,
                dds.length == 0 ? 0.0 : worst};
    }
}
