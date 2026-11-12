package opengraph.regime;

import static org.junit.jupiter.api.Assertions.*;

import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;

/** Miroirs de svd_regime.rs + engine.rs tests (regime). */
class SvdEngineTest {

    /** Indices devises regime : EUR GBP JPY USD AUD CAD CHF NZD — USD = 3. */
    static final int USD = 3;

    /** Choc USD pur : y_t = delta_t * (e_USD - 1/n). */
    static double[][] usdShock() {
        double[] deltas = {0.01, -0.02, 0.015, -0.005, 0.03, -0.01, 0.02,
            -0.025, 0.008, -0.012};
        double[][] y = new double[deltas.length][8];
        for (int t = 0; t < deltas.length; t++) {
            for (int i = 0; i < 8; i++) {
                y[t][i] = deltas[t] * (i == USD ? 1.0 - 1.0 / 8.0
                        : -1.0 / 8.0);
            }
        }
        return y;
    }

    /** Bruit diffus deterministe (somme nulle par colonne). */
    static double[][] diffuse() {
        int w = 40;
        double[][] y = new double[w][8];
        for (int t = 0; t < w; t++) {
            double colMean = 0.0;
            for (int k = 0; k < 8; k++) {
                colMean += (((k * 7 + t * 13 + 5) % 11) - 5.0) * 0.001;
            }
            colMean /= 8.0;
            for (int i = 0; i < 8; i++) {
                y[t][i] = (((i * 7 + t * 13 + 5) % 11) - 5.0) * 0.001
                        - colMean;
            }
        }
        return y;
    }

    /** Choc USD pur : D = 1, u1_USD^2 = 7/8, sigma8 = 0. */
    @Test
    void pure_usd_shock_gives_full_dominance() {
        SvdRegime r = SvdRegime.dominanceFromReturns(usdShock(), 0.5);
        assertNotNull(r);
        assertEquals(1.0, r.dominance, 1e-9);
        assertEquals(USD, r.dominant);
        assertEquals(7.0 / 8.0, r.loadingSq, 1e-9);
        assertTrue(Math.abs(r.sigma[7]) < 1e-9, "sigma8 = " + r.sigma[7]);
        for (int k = 1; k < 8; k++) {
            assertTrue(Math.abs(r.sigma[k]) < 1e-6,
                    "sigma" + (k + 1) + " = " + r.sigma[k]);
        }
    }

    /** Bruit diffus : D < 1/2 et D >= 1/7 (Prop. 3(ii)). */
    @Test
    void diffuse_noise_low_dominance() {
        SvdRegime r = SvdRegime.dominanceFromReturns(diffuse(), 0.5);
        assertNotNull(r);
        assertTrue(r.dominance < 0.5, "D = " + r.dominance);
        assertTrue(r.dominance >= 1.0 / 7.0 - 1e-9, "D = " + r.dominance);
        assertTrue(r.dominant == -1 || r.loadingSq < 0.5);
    }

    /** Gramien glissant = SVD directe a ~1e-9. */
    @Test
    void rolling_gram_matches_direct_svd() {
        double[][] y = diffuse();
        int w = 30;
        SvdRegime.RollingGram rg = new SvdRegime.RollingGram(w,
                Integer.MAX_VALUE);
        int last = -1;
        for (int t = 0; t < y.length; t++) {
            rg.push(y[t]);
            if (t >= w - 1) {
                last = t;
            }
        }
        double[][] window = new double[w][];
        System.arraycopy(y, last + 1 - w, window, 0, w);
        SvdRegime direct = SvdRegime.dominanceFromReturns(window, 0.5);
        SvdRegime rolling = rg.dominance(0.5);
        assertNotNull(direct);
        assertNotNull(rolling);
        for (int k = 0; k < 8; k++) {
            assertEquals(direct.sigma[k], rolling.sigma[k], 1e-9,
                    "sigma" + (k + 1));
        }
        assertEquals(direct.dominance, rolling.dominance, 1e-9);
        assertEquals(direct.dominant, rolling.dominant);
    }

    /** Fenetre non pleine -> indisponible (fail-closed E3). */
    @Test
    void rolling_gram_unavailable_until_full() {
        SvdRegime.RollingGram rg = new SvdRegime.RollingGram(10,
                Integer.MAX_VALUE);
        double[] y = new double[8];
        for (int i = 0; i < 8; i++) {
            y[i] = i * 0.001 - 0.0035;
        }
        rg.push(y);
        assertNull(rg.dominance(0.5));
        assertNull(rg.gram());
    }

    /** R antisymetrique -> valeurs singulieres appariees, D(R) <= 1/2.
     * sigma(R)^2 = valeurs propres de R^T R (R antisym => sym. PSD). */
    @Test
    void antisymmetric_r_has_paired_singular_values() {
        double[][] r = LinAlg.zeros();
        for (int i = 0; i < 8; i++) {
            for (int j = i + 1; j < 8; j++) {
                double v = (((i * 11 + j * 3 + 1) % 7)) * 0.001 - 0.003;
                r[i][j] = v;
                r[j][i] = -v;
            }
        }
        double[][] rtR = LinAlg.mul(LinAlg.transpose(r), r);
        LinAlg.Eig eig = LinAlg.jacobiEigen(rtR, 1e-18, 100);
        double[] s = new double[8];
        for (int k = 0; k < 8; k++) {
            s[k] = Math.sqrt(Math.max(eig.values[k], 0.0));
        }
        for (int k = 0; k < 7; k += 2) {
            assertEquals(s[k], s[k + 1], 1e-9,
                    "sigma" + (k + 1) + " != sigma" + (k + 2));
        }
        double e = 0.0;
        for (double v : s) {
            e += v * v;
        }
        double d = s[0] * s[0] / e;
        assertTrue(d <= 0.5 + 1e-9, "D(R) = " + d + " doit etre <= 1/2");
    }

    // --- engine.rs (regime) --------------------------------------------

    static RegimeEngine.RegimeParams params() {
        RegimeEngine.RegimeParams p = new RegimeEngine.RegimeParams();
        p.hTNs = 500.0;
        p.wT = 20;
        p.wMin = 4;
        p.mMin = 1e-9;
        p.wReturns = 10;
        p.gramRecomputeEvery = 50;
        p.qSvdMin = 0.5;
        p.lHops = 3;
        p.eps = 1e-12;
        p.gate = new Gate.GateParams(3.0, 0.5, 0.95, 0.02, 3_000);
        return p;
    }

    /** Instantane : sequence strictement croissante geree par publisher. */
    @Test
    void engine_produces_snapshots_each_step() {
        RegimeEngine e = new RegimeEngine(params(), TensionTest.k8());
        double[] v = {0.3, -0.1, 4.9, 0.0, -0.4, 0.3, -0.2, -0.6};
        RegimeSnapshot s = e.step(1_000,
                TensionTest.coherentXAt(v), TensionTest.spreads());
        assertEquals(1_000, s.eventTimeNs);
    }

    /** Rejeu : memes entrees => memes instantanes au bit pres (E2). */
    @Test
    void replay_is_bitwise_deterministic() {
        java.util.function.Function<Double, List<double[]>> run =
                (shift) -> {
                    RegimeEngine e = new RegimeEngine(params(),
                            TensionTest.k8());
                    List<double[]> out = new ArrayList<>();
                    double[] v = {0.3, -0.1, 4.9, 0.0, -0.4, 0.3, -0.2, -0.6};
                    for (int k = 0; k < 40; k++) {
                        v[3] += 0.0001 * ((k % 5) - 2.0) + shift;
                        RegimeSnapshot s = e.step(k * 1_000L,
                                TensionTest.coherentXAt(v),
                                TensionTest.spreads());
                        out.add(new double[] {
                            s.gateOpen ? 1.0 : 0.0,
                            s.eventTimeNs,
                            s.tTilde});
                    }
                    return out;
                };
        List<double[]> a = run.apply(0.0);
        List<double[]> b = run.apply(0.0);
        assertEquals(a.size(), b.size());
        for (int i = 0; i < a.size(); i++) {
            assertEquals(a.get(i)[0], b.get(i)[0]);
            assertEquals(a.get(i)[1], b.get(i)[1]);
            assertEquals(Double.doubleToLongBits(a.get(i)[2]),
                    Double.doubleToLongBits(b.get(i)[2]));
        }
    }

    /** Fraicheur : instantane perime => allowsSend faux (fail-closed). */
    @Test
    void stale_snapshot_blocks_send() {
        RegimeSnapshot s = new RegimeSnapshot(7, 1_000, true, 0.5, 0.3, -1, 1);
        assertTrue(RegimeSnapshot.allowsSend(s, 1_500, 1_000));
        assertFalse(RegimeSnapshot.allowsSend(s, 2_001, 1_000),
                "perime > stale_ns");
        assertFalse(RegimeSnapshot.allowsSend(s, 999, 1_000),
                "instantane du futur");
    }

    /** Flux synthetique : choc USD pur -> D eleve, c* = USD. */
    @Test
    void usd_shock_closes_gate_and_flags_usd() {
        RegimeEngine e = new RegimeEngine(params(), TensionTest.k8());
        double[] v0 = {0.3, -0.1, 4.9, 0.0, -0.4, 0.3, -0.2, -0.6};
        for (int k = 0; k < 60; k++) {
            e.step(k * 1_000L, TensionTest.coherentXAt(v0),
                    TensionTest.spreads());
        }
        double[] v = v0.clone();
        RegimeSnapshot last = null;
        for (int k = 60; k < 90; k++) {
            double d = 0.01 + 0.001 * (k % 3);
            for (int i = 0; i < 8; i++) {
                v[i] += d * (i == 3 ? 7.0 / 8.0 : -1.0 / 8.0);
            }
            double[][] x = TensionTest.coherentXAt(v);
            x[0][1] += 0.0004;
            x[1][0] -= 0.0004;
            last = e.step(k * 1_000L, x, TensionTest.spreads());
        }
        assertNotNull(last);
        double d = last.dominance;
        assertTrue(Double.isFinite(d), "D disponible apres warm-up");
        assertTrue(d > 0.9, "D = " + d + " attendu proche de 1 sous choc USD");
        assertEquals(3, last.dominant, "c* = USD");
        assertEquals(1, last.direction, "USD en hausse");
    }

    /** Marche parfaitement coherent : T brut = 0 chaque pas. */
    @Test
    void coherent_market_zero_tension() {
        RegimeEngine e = new RegimeEngine(params(), TensionTest.k8());
        double[] v = {0.3, -0.1, 4.9, 0.0, -0.4, 0.3, -0.2, -0.6};
        double[][] x = TensionTest.coherentXAt(v);
        double[][] r = Tension.residual(x, TensionTest.k8());
        assertNotNull(r);
        assertTrue(LinAlg.norm(r) < 1e-12);
        RegimeSnapshot s = e.step(0, x, TensionTest.spreads());
        assertEquals(0, s.eventTimeNs);
    }
}
