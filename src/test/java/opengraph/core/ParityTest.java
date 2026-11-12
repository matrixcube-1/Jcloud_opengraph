package opengraph.core;

import static org.junit.jupiter.api.Assertions.*;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

/**
 * Parite Rust<->Java sur fixture doree : le livre synthetique est
 * reconstruit a l'identique des deux cotes (meme ups, memes facteurs),
 * les sorties evalAt/execSeq/med doivent coincider a 1e-9 relatif.
 *
 * golden.tsv est genere par `cargo run --release --bin og_dump_fixture`
 * (engine/src/bin/og_dump_fixture.rs) — NE PAS editer a la main.
 */
class ParityTest {

    static final double REL_TOL = 1e-9;

    /** Livre identique a og_dump_fixture::book_ts(shift, distort). */
    static List<SymbolSeries> bookTs(int shift, boolean distort) {
        double[] ups = PathsEvalTest.ups();
        List<SymbolSeries> out = new ArrayList<>();
        for (String s : Universe.FX) {
            int a = Universe.ci(s.substring(0, 3));
            int b = Universe.ci(s.substring(3, 6));
            double mid = ups[a] / ups[b];
            int n = 4;
            long[] ts = new long[n];
            double[] bids = new double[n];
            double[] asks = new double[n];
            double[] avol = new double[n];
            double[] bvol = new double[n];
            Arrays.fill(avol, 1e9);
            Arrays.fill(bvol, 1e9);
            for (int k = 0; k < n; k++) {
                double m = distort && s.equals("CADJPY") && k >= shift
                        ? mid * 0.995 : mid;
                ts[k] = 1_000 + 100L * k;
                bids[k] = m * 0.9999;
                asks[k] = m * 1.0001;
            }
            out.add(new SymbolSeries(s, ts, bids, asks, avol, bvol));
        }
        return out;
    }

    /**
     * Miroir de og_dump_fixture::book_var — livre 3 ticks
     * {1000,1002,1004} ({1500,...} pour lateSym) : mid plat, facteur
     * `factor` sur jumpSym a k>=kJumpFrom, volume `vol` sur volSym a
     * k==kVolAt (1e9 sinon).
     */
    static List<SymbolSeries> bookVar(String jumpSym, int kJumpFrom,
                                      double factor, String volSym,
                                      int kVolAt, double vol,
                                      String lateSym) {
        double[] ups = PathsEvalTest.ups();
        List<SymbolSeries> out = new ArrayList<>();
        for (String s : Universe.FX) {
            int a = Universe.ci(s.substring(0, 3));
            int b = Universe.ci(s.substring(3, 6));
            double mid = ups[a] / ups[b];
            int n = 3;
            long[] ts = new long[n];
            double[] bids = new double[n];
            double[] asks = new double[n];
            double[] avol = new double[n];
            double[] bvol = new double[n];
            for (int k = 0; k < n; k++) {
                double m = s.equals(jumpSym) && k >= kJumpFrom
                        ? mid * factor : mid;
                double v = s.equals(volSym) && k == kVolAt
                        ? vol : 1e9;
                ts[k] = (s.equals(lateSym) ? 1_500 : 1_000) + 2L * k;
                bids[k] = m * 0.9999;
                asks[k] = m * 1.0001;
                avol[k] = v;
                bvol[k] = v;
            }
            out.add(new SymbolSeries(s, ts, bids, asks, avol, bvol));
        }
        return out;
    }

    /** Registre des livres exec2 — memes specs que le dump Rust. */
    static List<SymbolSeries> execBook(String name) {
        switch (name) {
            case "kill0":
                return bookVar("USDJPY", 1, 1.01, null, -1, 0.0, null);
            case "kill1":
                return bookVar("CADJPY", 2, 1.01, null, -1, 0.0, null);
            case "volp":
                return bookVar(null, -1, 1.0, "CADJPY", 2, 0.008, null);
            case "vollow":
                return bookVar(null, -1, 1.0, "CADJPY", 2, 0.0005, null);
            case "noq":
                return bookVar(null, -1, 1.0, null, -1, 0.0, "CADJPY");
            case "done_vg":
                return bookVar(null, -1, 1.0, null, -1, 0.0, null);
            default:
                throw new IllegalArgumentException("livre inconnu " + name);
        }
    }

    static List<String[]> golden() throws Exception {
        List<String[]> rows = new ArrayList<>();
        try (var in = ParityTest.class.getResourceAsStream("/golden.tsv")) {
            assertNotNull(in, "golden.tsv absent du classpath test");
            var br = new BufferedReader(
                    new InputStreamReader(in, StandardCharsets.UTF_8));
            String line;
            while ((line = br.readLine()) != null) {
                if (!line.isBlank()) {
                    rows.add(line.split("\t"));
                }
            }
        }
        return rows;
    }

    static void assertClose(double want, double got, String what) {
        if (Double.isNaN(want)) {
            assertTrue(Double.isNaN(got), what + " : attendu NaN");
            return;
        }
        double tol = REL_TOL * Math.max(1.0, Math.abs(want));
        assertEquals(want, got, tol,
                what + " : |rust-java| > " + tol);
    }

    @Test
    void eval_exec_med_match_rust_golden() throws Exception {
        Engine flat = new Engine(bookTs(2, false));
        Engine dist = new Engine(bookTs(2, true));
        Engine distMeds = new Engine(bookTs(0, true));
        PathTables pt = PathTables.build();
        ExecParams ep = new ExecParams(0.0, 0.8, 0.5, false);

        int nEval = 0;
        int nExec = 0;
        int nExec2 = 0;
        int nMed = 0;
        Map<String, Engine> execEngines = new HashMap<>();
        for (String[] c : golden()) {
            switch (c[0]) {
                case "eval": {
                    Engine e = c[1].equals("dist") ? dist : flat;
                    long t = Long.parseLong(c[2]);
                    int pi = Integer.parseInt(c[3]);
                    e.sync(t);
                    EvalResult r = e.evalAt(t, 5_000.0, 10.0, 1.0, pt);
                    if (pi < 0) {
                        assertNull(r, "eval null attendu " + String.join("|", c));
                    } else {
                        assertNotNull(r, "eval null " + String.join("|", c));
                        assertEquals(pi, r.pathIdx,
                                "path_idx " + String.join("|", c));
                        assertClose(Double.parseDouble(c[4]), r.netUsd,
                                "net " + String.join("|", c));
                        assertClose(Double.parseDouble(c[5]), r.dddUsd,
                                "ddd " + String.join("|", c));
                    }
                    nEval++;
                    break;
                }
                case "exec": {
                    Engine e = c[1].equals("dist") ? dist : flat;
                    long t = Long.parseLong(c[2]);
                    int pi = Integer.parseInt(c[3]);
                    String tag = c[4];
                    ExecOutcome o = e.execSeq(t, pi, ep, pt);
                    if (tag.equals("NONE")) {
                        assertNull(o, "exec null attendu");
                    } else if (tag.equals("DONE")) {
                        assertTrue(o instanceof ExecOutcome.Done,
                                "Done attendu " + String.join("|", c));
                        ExecOutcome.Done d = (ExecOutcome.Done) o;
                        assertClose(Double.parseDouble(c[5]), d.pnlUsd,
                                "pnl " + String.join("|", c));
                        assertEquals(Integer.parseInt(c[6]), d.filledLegs);
                        assertEquals(Integer.parseInt(c[7]), d.partialLegs);
                    } else {
                        assertTrue(o instanceof ExecOutcome.Abort,
                                "Abort attendu " + String.join("|", c));
                        ExecOutcome.Abort ab = (ExecOutcome.Abort) o;
                        // Rust {:?} : NoQuote/KillIoc/BelowQMin
                        String rn = tag.substring(6);
                        AbortReason wantReason =
                                rn.equals("NoQuote") ? AbortReason.NO_QUOTE
                                : rn.equals("KillIoc") ? AbortReason.KILL_IOC
                                : AbortReason.BELOW_QMIN;
                        assertEquals(wantReason, ab.reason, "reason");
                        assertClose(Double.parseDouble(c[5]), ab.pnlUsd,
                                "pnl " + String.join("|", c));
                        assertEquals(Integer.parseInt(c[6]), ab.filledLegs);
                        assertEquals(Integer.parseInt(c[7]), ab.partialLegs);
                    }
                    nExec++;
                    break;
                }
                case "exec2": {
                    Engine e = execEngines.computeIfAbsent(c[1],
                            k -> new Engine(execBook(k)));
                    long t = Long.parseLong(c[2]);
                    int pi = Integer.parseInt(c[3]);
                    ExecParams ep2 = new ExecParams(
                            Double.parseDouble(c[4]),
                            Double.parseDouble(c[5]),
                            Double.parseDouble(c[6]),
                            c[7].equals("1"));
                    ExecOutcome o = e.execSeq(t, pi, ep2, pt);
                    String tag = c[8];
                    if (tag.equals("NONE")) {
                        assertNull(o, "exec2 null attendu");
                    } else if (tag.equals("DONE")) {
                        assertTrue(o instanceof ExecOutcome.Done,
                                "Done attendu " + String.join("|", c));
                        ExecOutcome.Done d = (ExecOutcome.Done) o;
                        assertClose(Double.parseDouble(c[9]), d.pnlUsd,
                                "pnl " + String.join("|", c));
                        assertEquals(Integer.parseInt(c[10]), d.filledLegs);
                        assertEquals(Integer.parseInt(c[11]), d.partialLegs);
                        assertClose(Double.parseDouble(c[12]), d.durMs,
                                "dur " + String.join("|", c));
                    } else {
                        assertTrue(o instanceof ExecOutcome.Abort,
                                "Abort attendu " + String.join("|", c));
                        ExecOutcome.Abort ab = (ExecOutcome.Abort) o;
                        String rn = tag.substring(6);
                        AbortReason wantReason =
                                rn.equals("NoQuote") ? AbortReason.NO_QUOTE
                                : rn.equals("KillIoc") ? AbortReason.KILL_IOC
                                : AbortReason.BELOW_QMIN;
                        assertEquals(wantReason, ab.reason, "reason");
                        assertClose(Double.parseDouble(c[9]), ab.pnlUsd,
                                "pnl " + String.join("|", c));
                        assertEquals(Integer.parseInt(c[10]), ab.filledLegs);
                        assertEquals(Integer.parseInt(c[11]), ab.partialLegs);
                        assertClose(Double.parseDouble(c[12]), ab.durMs,
                                "dur " + String.join("|", c));
                    }
                    nExec2++;
                    break;
                }
                case "med": {
                    String sym = c[2];
                    Integer si = distMeds.sidx.get(sym);
                    assertNotNull(si, "sym inconnu " + sym);
                    assertClose(Double.parseDouble(c[3]), distMeds.med[si],
                            "med " + sym);
                    nMed++;
                    break;
                }
                default:
                    fail("kind inconnu: " + c[0]);
            }
        }
        assertEquals(6, nEval, "lignes eval attendues");
        assertEquals(2, nExec, "lignes exec attendues");
        assertEquals(6, nExec2, "lignes exec2 attendues");
        assertEquals(28, nMed, "lignes med attendues");
    }
}
