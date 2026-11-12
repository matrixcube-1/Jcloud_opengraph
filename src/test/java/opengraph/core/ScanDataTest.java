package opengraph.core;

import static org.junit.jupiter.api.Assertions.*;

import java.nio.file.Files;
import java.util.List;
import org.junit.jupiter.api.Test;

/**
 * Miroirs de scan.rs + data.rs tests.
 */
class ScanDataTest {

    /** Livre avec CADJPY deforme a partir du tick `shiftCadjpy`. */
    static List<SymbolSeries> bookTs(int shiftCadjpy) {
        double[] ups = PathsEvalTest.ups();
        java.util.List<SymbolSeries> out = new java.util.ArrayList<>();
        for (String s : Universe.FX) {
            int a = Universe.ci(s.substring(0, 3));
            int b = Universe.ci(s.substring(3, 6));
            double mid = ups[a] / ups[b];
            int nTicks = 4;
            long[] ts = new long[nTicks];
            double[] bids = new double[nTicks];
            double[] asks = new double[nTicks];
            for (int k = 0; k < nTicks; k++) {
                double m = s.equals("CADJPY") && k >= shiftCadjpy
                        ? mid * 0.995 : mid;
                ts[k] = 1_000 + 100L * k;
                bids[k] = m * 0.9999;
                asks[k] = m * 1.0001;
            }
            out.add(new SymbolSeries(s, ts, bids, asks,
                    new double[nTicks], new double[nTicks]));
        }
        // avol/bvol a 1.0 — le constructeur recoit des zeros ; corriger :
        for (SymbolSeries ss : out) {
            java.util.Arrays.fill(ss.avol, 1.0);
            java.util.Arrays.fill(ss.bvol, 1.0);
        }
        return out;
    }

    /** Le scan detecte l'ouverture de la distorsion : candidats + episode. */
    @Test
    void scan_detects_distortion_episode() {
        Engine e = new Engine(bookTs(2));
        PathTables pt = PathTables.build();
        Engine.ScanOut so = e.scan(pt, 5_000.0, 10.0, -30.0, 0.25, 500);
        assertTrue(so.stats.nEvents > 0 && so.stats.nEvals > 0);
        assertFalse(so.cands.isEmpty());
        assertFalse(so.episodes.isEmpty());
        assertTrue(so.episodes.stream().anyMatch(ep -> ep.netMax > 0.0));
    }

    /** T6 (parite) : rejouer deux fois les memes candidats = identique. */
    @Test
    void replay_is_deterministic_across_calls() {
        Engine e = new Engine(bookTs(2));
        PathTables pt = PathTables.build();
        Engine.ScanOut so = e.scan(pt, 5_000.0, 10.0, -30.0, 0.25, 500);
        Engine.Params dp = Engine.Params.docDefaults();
        Engine.ReplayResult r1 = e.replay(so.cands, dp, pt);
        Engine.ReplayResult r2 = e.replay(so.cands, dp, pt);
        assertEquals(r1.pnl, r2.pnl, "fuite d'etat inter-replays");
        assertEquals(r1.trades, r2.trades);
        assertEquals(r1.aborts, r2.aborts);
    }

    /** Cooldown : verrou anti-rechevauchement reduit les trades. */
    @Test
    void replay_cooldown_limits_trades() {
        Engine e = new Engine(bookTs(2));
        PathTables pt = PathTables.build();
        Engine.ScanOut so = e.scan(pt, 5_000.0, 10.0, -30.0, 0.25, 500);
        Engine.Params pNo = Engine.Params.docDefaults();
        Engine.Params pCd = Engine.Params.docDefaults();
        pCd.cooldownS = 60.0;
        Engine.ReplayResult r1 = e.replay(so.cands, pNo, pt);
        Engine.ReplayResult r2 = e.replay(so.cands, pCd, pt);
        assertTrue(r2.trades <= r1.trades);
    }

    // --- data.rs --------------------------------------------------------

    @Test
    void civil_epoch_spot_checks() {
        assertEquals(0, DataLoader.epochMs("1970-01-01"));
        assertEquals(1_792_627_200_000L, DataLoader.epochMs("2026-10-22"));
        assertEquals(86_400_000L,
                DataLoader.epochMs("2026-10-23")
                        - DataLoader.epochMs("2026-10-22"));
        assertEquals(3 * 86_400_000L,
                DataLoader.epochMs("2026-10-26")
                        - DataLoader.epochMs("2026-10-23"));
    }

    @Test
    void load_symbol_reads_hours_and_combines_vol() throws Exception {
        java.nio.file.Path dir = Files.createTempDirectory("og_test_data");
        java.nio.file.Path f = dir.resolve("EURUSD").resolve("2026-10-22");
        Files.createDirectories(f);
        Files.writeString(f.resolve("00.csv"),
                "ms_off,ask,bid,ask_vol,bid_vol\n"
                        + "0,1.1001,1.0999,2.0,1.0\n"
                        + "500,1.1003,1.1001,0.5,3.0\n");
        Files.writeString(f.resolve("01.csv"),
                "ms_off,ask,bid,ask_vol,bid_vol\n"
                        + "100,1.1010,1.1008,1.5,1.5\n");
        SymbolSeries s = DataLoader.loadSymbol(dir, "EURUSD",
                new String[] {"2026-10-22"});
        assertNotNull(s);
        assertEquals(3, s.len());
        assertEquals(DataLoader.epochMs("2026-10-22"), s.ts[0]);
        assertEquals(DataLoader.epochMs("2026-10-22") + 3_600_000 + 100,
                s.ts[2]);
        assertEquals(2.0, s.avol[0]);
        assertEquals(1.0, s.bvol[0]);
        assertEquals(0.5, s.avol[1]);
        assertEquals(3.0, s.bvol[1]);
        assertEquals(1.1010, s.ask[2]);
        deleteTree(dir);
    }

    /** >5 % de lignes malformees (et >=5) = corruption -> fail-fast. */
    @Test
    void corrupt_csv_fails_fast_over_threshold() throws Exception {
        java.nio.file.Path dir = Files.createTempDirectory("og_test_corrupt");
        java.nio.file.Path f = dir.resolve("EURUSD").resolve("2026-10-22");
        Files.createDirectories(f);
        StringBuilder csv = new StringBuilder(
                "ms_off,ask,bid,ask_vol,bid_vol\n");
        for (int i = 0; i < 10; i++) {
            csv.append("bad").append(i).append(",x,y,z,w\n");
        }
        csv.append("0,1.1001,1.0999,2.0,1.0\n"); // 10/11 rejetees
        Files.writeString(f.resolve("00.csv"), csv.toString());
        assertThrows(java.io.IOException.class,
                () -> DataLoader.loadSymbol(dir, "EURUSD",
                        new String[] {"2026-10-22"}));
        deleteTree(dir);
    }

    /** Litteraux non finis et quotes croisees = lignes rejetees. */
    @Test
    void nan_and_crossed_lines_are_rejected() throws Exception {
        java.nio.file.Path dir = Files.createTempDirectory("og_test_nan");
        java.nio.file.Path f = dir.resolve("EURUSD").resolve("2026-10-22");
        Files.createDirectories(f);
        StringBuilder csv = new StringBuilder(
                "ms_off,ask,bid,ask_vol,bid_vol\n");
        for (int i = 0; i < 19; i++) {
            csv.append(i).append(",1.1001,1.0999,2.0,1.0\n");
        }
        csv.append("20,NaN,1.0999,2.0,1.0\n");   // litteral non fini
        csv.append("21,1.0998,1.0999,2.0,1.0\n"); // croisee : ask < bid
        Files.writeString(f.resolve("00.csv"), csv.toString());
        SymbolSeries s = DataLoader.loadSymbol(dir, "EURUSD",
                new String[] {"2026-10-22"});
        assertNotNull(s);
        assertEquals(19, s.len(), "NaN/croisee chargees comme ticks valides");
        deleteTree(dir);
    }

    /** <5 % de lignes malformees : skip + avertissement, pas fatal. */
    @Test
    void few_malformed_lines_skipped_not_fatal() throws Exception {
        java.nio.file.Path dir = Files.createTempDirectory("og_test_warn");
        java.nio.file.Path f = dir.resolve("EURUSD").resolve("2026-10-22");
        Files.createDirectories(f);
        StringBuilder csv = new StringBuilder(
                "ms_off,ask,bid,ask_vol,bid_vol\n");
        for (int i = 0; i < 20; i++) {
            csv.append(i).append(",1.1001,1.0999,2.0,1.0\n");
        }
        csv.append("ligne tronquee\n"); // 1/21 < 5 % -> toleree
        Files.writeString(f.resolve("00.csv"), csv.toString());
        SymbolSeries s = DataLoader.loadSymbol(dir, "EURUSD",
                new String[] {"2026-10-22"});
        assertNotNull(s);
        assertEquals(20, s.len());
        deleteTree(dir);
    }

    private static void deleteTree(java.nio.file.Path dir)
            throws java.io.IOException {
        if (!Files.exists(dir)) {
            return;
        }
        try (var walk = Files.walk(dir)) {
            walk.sorted(java.util.Comparator.reverseOrder())
                    .forEach(p -> {
                        try {
                            Files.delete(p);
                        } catch (java.io.IOException ignored) {}
                    });
        }
    }
}
