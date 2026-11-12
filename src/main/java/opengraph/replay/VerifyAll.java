package opengraph.replay;

import java.lang.management.ManagementFactory;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import opengraph.core.Bout;
import opengraph.core.BoutUniverse;
import opengraph.core.DataLoader;
import opengraph.core.Engine;
import opengraph.core.EvalResult;
import opengraph.core.ExecOutcome;
import opengraph.core.ExecParams;
import opengraph.core.ExtPathTables;
import opengraph.core.PathTables;
import opengraph.core.Quote;
import opengraph.core.Universe;
import opengraph.validation.MonteCarlo;
import opengraph.validation.ProofChecks;

/**
 * Verification en chaine de bout en bout — UNE commande qui declenche
 * tout le processus d'arborescence : S1 structure -> S2 mapping par
 * injection -> S3/S4 formules a la centime -> S5 puissance -> S6
 * allocations -> MC (Monte-Carlo E9) en bout final.
 *
 * Chaque etape s'arrete au premier echec (fail fast) ; code retour =
 * nombre d'etapes echouees. Usage :
 *   java opengraph.replay.VerifyAll [dataRoot]
 */
public final class VerifyAll {

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

    static int fails = 0;

    static void ok(boolean cond, String name, String detail) {
        if (cond) {
            System.out.printf("  [OK]   %-58s %s%n", name, detail);
        } else {
            fails++;
            System.out.printf("  [FAIL] %-58s %s%n", name, detail);
        }
    }

    static void book(Engine e, long t) {
        for (String p : Universe.FX) {
            double mid = USD_VAL.get(p.substring(0, 3))
                    / USD_VAL.get(p.substring(3));
            e.ingest(e.sidx.get(p), new Quote(t, mid * (1 - 0.5e-4),
                    mid * (1 + 0.5e-4), 1e6));
        }
        for (Bout b : BoutUniverse.BOUTS) {
            e.ingest(e.sidx.get(b.name), new Quote(t, 99.99, 100.01, 1e6));
        }
    }

    public static void main(String[] args) throws Exception {
        String root = args.length > 0 ? args[0] : null;
        PathTables pt = PathTables.build();
        ExtPathTables xt = ExtPathTables.build(pt);

        System.out.println("=== S1 ARBORESCENCE : comptes + forme close ===");
        ok(Universe.FX.length == 28, "28 paires FX", "" + Universe.FX.length);
        ok(BoutUniverse.BOUTS.length == 26, "26 bouts",
                "" + BoutUniverse.BOUTS.length);
        List<String> bad = ProofChecks.run(pt, xt);
        ok(bad.isEmpty(), "ProofChecks Annexe H (2072/259/8806/puits)",
                bad.isEmpty() ? "vide" : bad.toString());
        // pas de cycle ni de noeud repete
        int cyc = 0;
        for (int pi = 0; pi < pt.len(); pi++) {
            boolean[] seen = new boolean[8];
            int cur = pt.pS[pi];
            if (seen[cur]) {
                cyc++;
            }
            seen[cur] = true;
            for (int li = 0; li < pt.pL[pi]; li++) {
                int e = pt.pE[pi][li];
                int[] nn = Universe.pairNodes(e);
                int nxt = nn[0] == cur ? nn[1] : nn[0];
                if (seen[nxt]) {
                    cyc++;
                }
                seen[nxt] = true;
                cur = nxt;
            }
        }
        ok(cyc == 0, "0 cycle / 0 noeud repete sur " + pt.len() + " chemins",
                "cyc=" + cyc);
        if (fails > 0) {
            exit("S1");
        }

        System.out.println("=== S2 MAPPING : preuve par injection ===");
        Engine e = Engine.live();
        book(e, 1_000);
        EvalResult r = e.evalAt(1_000, 8000, 1.7, 1.0, pt);
        ok(r != null && r.netUsd < 0 && r.netUsd > -10,
                "livre coherent -> plancher cout", fmt(r));
        // desynchro EURJPY +1 % : le gagnant doit traverser EUR->JPY
        int sj = e.sidx.get("EURJPY");
        e.ingest(sj, new Quote(2_000, e.bid[sj] * 1.01, e.ask[sj] * 1.01, 1e6));
        EvalResult r2 = e.evalAt(2_000, 8000, 1.7, 1.0, pt);
        boolean dir = false;
        if (r2 != null) {
            int pi = r2.pathIdx;
            for (int li = 0; li < pt.pL[pi]; li++) {
                if ("EURJPY".equals(Universe.FX[pt.pE[pi][li]])
                        && pt.pDir[pi][li] == 1) {
                    dir = true;
                }
            }
        }
        ok(r2 != null && dir && r2.netUsd > 50 && r2.netUsd < 120,
                "desynchro EURJPY -> gagnant EUR->JPY, net dans l'enveloppe",
                fmt(r2));
        if (fails > 0) {
            exit("S2");
        }

        System.out.println("=== S3/S4 FORMULES : calcul main = moteur ===");
        double rate = (1.0 / (1.10 * (1 + 0.5e-4)))
                * (165.0 * (1 - 0.5e-4) * 1.01)
                * (1.0 / (150.0 * (1 + 0.5e-4)));
        double usdOut = Fees_NOTIONAL() * rate;
        double comm = 3 * 2 * e.commPerSide;           // 3 positions aller-retour
        double unwind = (askBid(1.10) + askBid(165.0 * 1.01) + askBid(150.0))
                * Fees_NOTIONAL();
        double netMain = usdOut - Fees_NOTIONAL() - comm - unwind;
        ok(r2 != null && Math.abs(r2.netUsd - netMain) < 0.01,
                "net moteur = net main a 0.01 pres",
                String.format("moteur=%.4f main=%.4f", r2.netUsd, netMain));

        System.out.println("=== S5/S6 PUISSANCE + ALLOCATIONS ===");
        Engine eb = Engine.live();
        book(eb, 1_000);
        for (int i = 0; i < 3_000; i++) {
            eb.evalAt(1_000, 8000, 1.7, 1.0, pt);
            eb.evalBoutAt(1_000, 8000, 1.7, 1.0, pt, xt);
        }
        double usA = time(eb, 1_000, 2_000, pt, null);
        double usB = time(eb, 1_000, 1_000, pt, xt);
        ok(usA < 1_000, "evalAt < 1 ms", String.format("%.0f us", usA));
        ok(usB < 1_000, "evalBoutAt < 1 ms", String.format("%.0f us", usB));
        long a = allocated();
        for (int i = 0; i < 1_000; i++) {
            eb.evalAt(1_000, 8000, 1.7, 1.0, pt);
        }
        long perEval = (allocated() - a) / 1_000;
        ok(perEval <= 64, "evalAt sans alloc (<64 o)",
                perEval + " o/eval");

        System.out.println("=== MC BOUT FINAL : Monte-Carlo E9 ===");
        // chemin gagnant du livre perturbe — exec reel fill-par-fill
        ExecParams ep = new ExecParams(1.0, 0.69, 0.5, false);
        MonteCarlo.Summary s1 = MonteCarlo.run(e, 2_000, r2.pathIdx, ep, pt,
                300, 1.0, 7);
        ok(s1.died == 0 && s1.done + s1.aborted == 300,
                "survie=1.0 -> 300 exec reelles (done+abort)",
                String.format("done=%d abort=%d died=%d meanPnl=%.3f",
                        s1.done, s1.aborted, s1.died, s1.meanRealizable()));
        MonteCarlo.Summary s0 = MonteCarlo.run(e, 2_000, r2.pathIdx, ep, pt,
                300, 0.0, 7);
        ok(s0.died == 300 && s0.pnlRealizableSum == 0.0,
                "survie=0.0 -> tout meurt, aucun gain compte",
                String.format("died=%d pnl=%.2f", s0.died,
                        s0.pnlRealizableSum));
        // donnees reelles : eval + MC sur le meilleur chemin observe
        boolean corpus = root != null
                && java.nio.file.Files.isDirectory(java.nio.file.Paths.get(root));
        if (!corpus) {
            System.out.println("  [SKIP] corpus reel absent — la chaine reste"
                    + " prouvee sur livre synthetique + MC ; fournir"
                    + " dataRoot pour activer le replay corpus");
        } else try {
            Engine er = DataLoader.loadFxEngine(java.nio.file.Paths.get(root),
                    new String[] {"2026-10-22"});
            long tm = er.ev.get(er.ev.size() / 2)[0];
            er.sync(tm);
            EvalResult rr = er.evalAt(tm, 8000, 1.7, 1.0, pt);
            if (rr == null) {
                ok(false, "corpus reel : evalAt retourne un resultat", "null");
            } else {
                ExecOutcome ex = er.execSeq(tm, rr.pathIdx, ep, pt);
                ok(ex != null, "corpus reel : eval -> execSeq chaine",
                        fmt(rr) + " | exec=" + ex.getClass().getSimpleName()
                                + " pnl=" + String.format("%.3f", ex.pnl()));
                MonteCarlo.Summary sm = MonteCarlo.run(er, tm, rr.pathIdx, ep,
                        pt, 200, 0.8, 11);
                ok(sm.trials == 200,
                        "MC reel : 200 tirages conditionnes survie=0.8",
                        String.format("done=%d abort=%d died=%d realis=%.3f",
                                sm.done, sm.aborted, sm.died,
                                sm.meanRealizable()));
            }
        } catch (Exception ex) {
            ok(false, "corpus reel chargeable sous " + root,
                    String.valueOf(ex.getMessage()));
        }

        System.out.println();
        if (fails == 0) {
            System.out.println("=== VERDICT : CHAINE COMPLETE — TOUT VERT ===");
        } else {
            System.out.println("=== VERDICT : " + fails + " ETAPE(S) EN ECHEC ===");
        }
        System.exit(fails);
    }

    static void exit(String stage) {
        System.out.println("ARRET CHAINE a " + stage + " (fail-fast)");
        System.exit(fails);
    }

    static double Fees_NOTIONAL() {
        return opengraph.core.Fees.NOTIONAL_USD;
    }

    static double askBid(double mid) {
        return (mid * (1 + 0.5e-4) - mid * (1 - 0.5e-4)) / mid;
    }

    static String fmt(EvalResult r) {
        return r == null ? "null"
                : String.format("net=%.4f pi=%d ddd=%.4f", r.netUsd,
                        r.pathIdx, r.dddUsd);
    }

    static long allocated() {
        return ((com.sun.management.ThreadMXBean) ManagementFactory
                .getThreadMXBean()).getThreadAllocatedBytes(
                        Thread.currentThread().getId());
    }

    static double time(Engine e, long t, int n, PathTables pt,
                       ExtPathTables xt) {
        long a = System.nanoTime();
        for (int i = 0; i < n; i++) {
            if (xt == null) {
                e.evalAt(t, 8000, 1.7, 1.0, pt);
            } else {
                e.evalBoutAt(t, 8000, 1.7, 1.0, pt, xt);
            }
        }
        return (System.nanoTime() - a) / 1e3 / n;
    }
}
