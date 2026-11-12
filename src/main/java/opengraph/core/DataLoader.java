package opengraph.core;

import java.io.BufferedReader;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.List;

/**
 * Chargement des ticks CSV layout /opt/arb/data — port de data.rs.
 *
 * <pre>
 * &lt;root&gt;/&lt;SYM&gt;/&lt;YYYY-MM-DD&gt;/&lt;HH&gt;.csv
 * header: ms_off,ask,bid,ask_vol,bid_vol
 * ts_ms = epoch_ms(day) + h*3_600_000 + ms_off   (UTC)
 * </pre>
 *
 * Ticks tries par ts de facon stable (ordre de lecture conserve a
 * timestamp egal, comme np.argsort stable).
 */
public final class DataLoader {

    private DataLoader() {}

    /** Jours du POC (identiques a poc_engine.DAYS). */
    public static final String[] DAYS = {
        "2026-10-22", "2026-10-23", "2026-10-26"
    };

    /** Jours depuis epoch Unix (Howard Hinnant, civil -> days). */
    private static long daysFromCivil(long y, long m, long d) {
        long yy = m <= 2 ? y - 1 : y;
        long era = Math.floorDiv(yy >= 0 ? yy : yy - 399, 400);
        long yoe = yy - era * 400;
        long mp = Math.floorMod(m + 9, 12);
        long doy = (153 * mp + 2) / 5 + d - 1;
        long doe = yoe * 365 + yoe / 4 - yoe / 100 + doy;
        return era * 146_097 + doe - 719_468;
    }

    /** epoch_ms("YYYY-MM-DDT00:00:00Z"). */
    public static long epochMs(String day) {
        String[] p = day.split("-");
        return daysFromCivil(Long.parseLong(p[0]), Long.parseLong(p[1]),
                Long.parseLong(p[2])) * 86_400_000L;
    }

    private static class Row {
        long ts;
        double ask, bid, avol, bvol;
        int order; // tie-break stable
    }

    /**
     * Charge un symbole sur les jours donnes ; null si aucun tick.
     * Lignes malformees/non finies/croisees rejetees ; exception si
     * >= 5 rejetees ET > 5 % du fichier (corruption reelle).
     */
    public static SymbolSeries loadSymbol(Path root, String sym,
                                          String[] days) throws IOException {
        List<Row> rows = new ArrayList<>();
        int ord = 0;
        for (String day : days) {
            long t0 = epochMs(day);
            for (int h = 0; h < 24; h++) {
                Path f = root.resolve(sym).resolve(day)
                        .resolve(String.format("%02d.csv", h));
                if (!Files.isRegularFile(f)) {
                    continue; // heure absente = trou de couverture
                }
                long base = t0 + h * 3_600_000L;
                int total = 0;
                int rejected = 0;
                try (BufferedReader r = Files.newBufferedReader(f)) {
                    String line = r.readLine(); // header
                    while ((line = r.readLine()) != null) {
                        total++;
                        String[] c = line.split(",", -1);
                        if (c.length < 5) {
                            rejected++;
                            continue;
                        }
                        try {
                            long ms = Long.parseLong(c[0].trim());
                            double a = Double.parseDouble(c[1].trim());
                            double b = Double.parseDouble(c[2].trim());
                            double av = Double.parseDouble(c[3].trim());
                            double bv = Double.parseDouble(c[4].trim());
                            // litteraux non finis et quotes croisees rejetes
                            if (!(Double.isFinite(a) && Double.isFinite(b)
                                    && Double.isFinite(av)
                                    && Double.isFinite(bv)) || a < b) {
                                rejected++;
                                continue;
                            }
                            Row row = new Row();
                            row.ts = base + ms;
                            row.ask = a;
                            row.bid = b;
                            row.avol = av;
                            row.bvol = bv;
                            row.order = ord++;
                            rows.add(row);
                        } catch (NumberFormatException nfe) {
                            rejected++;
                        }
                    }
                }
                if (rejected > 0) {
                    System.err.println("[data] " + f + " : " + rejected + "/"
                            + total + " lignes rejetees (malformees)");
                    if (rejected >= 5 && rejected * 20 > total) {
                        throw new IOException("fichier corrompu " + f + " : "
                                + rejected + "/" + total
                                + " lignes rejetees");
                    }
                }
            }
        }
        if (rows.isEmpty()) {
            return null;
        }
        // tri stable par ts — order preserve l'ordre de lecture a ts egal
        rows.sort(java.util.Comparator.comparingLong((Row r) -> r.ts)
                .thenComparingInt(r -> r.order));
        int n = rows.size();
        long[] ts = new long[n];
        double[] bid = new double[n];
        double[] ask = new double[n];
        double[] avol = new double[n];
        double[] bvol = new double[n];
        for (int i = 0; i < n; i++) {
            Row r = rows.get(i);
            ts[i] = r.ts;
            bid[i] = r.bid;
            ask[i] = r.ask;
            avol[i] = r.avol;
            bvol[i] = r.bvol;
        }
        return new SymbolSeries(sym, ts, bid, ask, avol, bvol);
    }

    /** Charge toutes les paires FX presentes sous root ; construit l'Engine. */
    public static Engine loadFxEngine(Path root, String[] days)
            throws IOException {
        List<SymbolSeries> series = new ArrayList<>();
        for (String s : Universe.FX) {
            SymbolSeries ss = loadSymbol(root, s, days);
            if (ss != null) {
                series.add(ss);
            }
        }
        return new Engine(series);
    }

    /**
     * Charge l'univers complet : 28 FX + 26 bouts J.3 (presents sous root).
     * Les symboles bout absents du corpus sont ignores (boutSi=-1, leurs
     * chemins etendus sont simplement non evaluables).
     */
    public static Engine loadFullEngine(Path root, String[] days)
            throws IOException {
        List<SymbolSeries> series = new ArrayList<>();
        for (String s : Universe.FX) {
            SymbolSeries ss = loadSymbol(root, s, days);
            if (ss != null) {
                series.add(ss);
            }
        }
        for (String s : BoutUniverse.symbols()) {
            SymbolSeries ss = loadSymbol(root, s, days);
            if (ss != null) {
                series.add(ss);
            }
        }
        return new Engine(series);
    }

    /** Racine du corpus local par defaut. */
    public static Path defaultRoot() {
        return Paths.get("C:\\arb_duka\\data");
    }
}
