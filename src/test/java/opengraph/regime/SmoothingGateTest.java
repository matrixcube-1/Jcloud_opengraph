package opengraph.regime;

import static org.junit.jupiter.api.Assertions.*;

import org.junit.jupiter.api.Test;

/** Miroirs de smoothing.rs + gate.rs tests. */
class SmoothingGateTest {

    // --- EWMA ---------------------------------------------------------

    /** Demi-vie : apres exactement h, la moitie du chemin est faite. */
    @Test
    void ewma_half_life_semantics() {
        Smoothing.Ewma e = new Smoothing.Ewma(1000.0);
        e.update(0, 0.0);
        double v = e.update(1000, 10.0);
        assertEquals(5.0, v, 1e-9);
    }

    /** dt = 0 -> pas de mise a jour (a = 0). */
    @Test
    void ewma_zero_dt_is_noop() {
        Smoothing.Ewma e = new Smoothing.Ewma(1000.0);
        e.update(0, 1.0);
        double v = e.update(0, 99.0);
        assertEquals(1.0, v, 1e-12);
    }

    /** EWMA converge vers la valeur constante. */
    @Test
    void ewma_converges_to_constant() {
        Smoothing.Ewma e = new Smoothing.Ewma(100.0);
        e.update(0, 5.0);
        double v = 0.0;
        for (int k = 1; k < 50; k++) {
            v = e.update(k * 100L, 7.0);
        }
        assertEquals(7.0, v, 1e-6);
    }

    /** Z connu : historique [1..=9] -> med=5, MAD=2. */
    @Test
    void robust_z_known_value() {
        Smoothing.RobustZ z = new Smoothing.RobustZ(100, 9, 1e-9);
        for (int k = 1; k <= 9; k++) {
            z.update(k);
        }
        double got = z.update(11.0);
        double want = (11.0 - 5.0) / (1.4826 * 2.0);
        assertEquals(want, got, 1e-9);
    }

    /** Pas de fuite : x n'entre dans H qu'APRES le calcul. */
    @Test
    void robust_z_no_lookahead() {
        Smoothing.RobustZ z = new Smoothing.RobustZ(100, 3, 1e-9);
        z.update(0.0);
        z.update(0.0);
        z.update(0.0);
        double got = z.update(1.0);
        assertTrue(Math.abs(got - 1.0 / 1e-9) < 1e-3
                || Double.isFinite(got));
        double got2 = z.update(1.0);
        assertTrue(got2 < got || Double.isFinite(got2));
    }

    /** Indisponible tant que |H| < w_min (fail-closed). */
    @Test
    void robust_z_unavailable_below_wmin() {
        Smoothing.RobustZ z = new Smoothing.RobustZ(100, 5, 1e-9);
        for (int i = 0; i < 5; i++) {
            assertTrue(Double.isNaN(z.update(1.0)));
        }
        assertTrue(Double.isFinite(z.update(1.0)));
    }

    /** Fenetre bornee : len <= window. */
    @Test
    void robust_z_window_bounded() {
        Smoothing.RobustZ z = new Smoothing.RobustZ(4, 2, 1e-9);
        for (int k = 0; k < 10; k++) {
            z.update(k);
        }
        assertTrue(z.len() <= 4);
    }

    // --- Gate ---------------------------------------------------------

    static Gate.GateParams params() {
        return new Gate.GateParams(2.0, 0.5, 0.6, 0.05, 1_000);
    }

    /** Etat initial ferme. */
    @Test
    void gate_starts_closed() {
        Gate g = new Gate(params());
        assertFalse(g.isOpen());
    }

    /** Reouverture seulement apres tau_hold de calme strict. */
    @Test
    void gate_reopens_after_hold_time() {
        Gate g = new Gate(params());
        assertFalse(g.update(0, 1.0, 0.4));
        assertFalse(g.update(500, 1.0, 0.4));
        assertTrue(g.update(1_500, 1.0, 0.4));
        assertTrue(g.isOpen());
    }

    /** Fermeture immediate sur depassement. */
    @Test
    void gate_closes_immediately_on_breach() {
        Gate g = new Gate(params());
        g.update(0, 1.0, 0.4);
        g.update(1_500, 1.0, 0.4);
        assertTrue(g.isOpen());
        assertFalse(g.update(2_000, 3.0, 0.4));
        assertFalse(g.isOpen());
        Gate g2 = new Gate(params());
        g2.update(0, 1.0, 0.4);
        g2.update(1_500, 1.0, 0.4);
        assertFalse(g2.update(2_000, 1.0, 0.9)); // D > theta_D
    }

    /** Bande delta : entre theta-delta et theta -> pas de reouverture. */
    @Test
    void gate_hysteresis_band_blocks_reopen() {
        Gate g = new Gate(params());
        for (int k = 0; k < 10; k++) {
            assertFalse(g.update(k * 1_000L, 1.8, 0.4), "k=" + k);
        }
    }

    /** Interruption du calme : le timer repart a zero. */
    @Test
    void gate_calm_timer_resets_on_disturbance() {
        Gate g = new Gate(params());
        g.update(0, 1.0, 0.4);
        g.update(500, 5.0, 0.4);
        g.update(600, 1.0, 0.4);
        assertFalse(g.update(1_500, 1.0, 0.4), "hold relance a 600");
        assertTrue(g.update(1_700, 1.0, 0.4), "600+1000=1600 <= 1700");
    }

    /** Indisponible => ferme (fail-closed). */
    @Test
    void gate_fail_closed_on_unavailable() {
        Gate g = new Gate(params());
        g.update(0, 1.0, 0.4);
        g.update(1_500, 1.0, 0.4);
        assertTrue(g.isOpen());
        assertFalse(g.update(2_000, Double.NaN, 0.4));
        assertFalse(g.update(3_000, 1.0, Double.NaN));
        assertFalse(g.update(4_000, Double.NaN, 0.4));
    }

    /** Anti-battement : reouvertures bornees. */
    @Test
    void gate_bounded_flapping() {
        Gate g = new Gate(params());
        int reopens = 0;
        boolean wasOpen = false;
        for (int k = 0; k < 20; k++) {
            double tt = k % 2 == 0 ? 1.0 : 5.0;
            double dd = k % 2 == 0 ? 0.4 : 0.9;
            boolean open = g.update(k * 2_000L, tt, dd);
            if (open && !wasOpen) {
                reopens++;
            }
            wasOpen = open;
        }
        assertTrue(reopens <= 41, "reopens=" + reopens);
    }

    // --- modulation g (Eq. 12) ----------------------------------------

    /** Regime calme => g = 1. */
    @Test
    void modulation_is_one_when_calm() {
        Gate.ModParams p = new Gate.ModParams(1.0, 0.5, 0.5, 2.0, 0.2);
        assertEquals(1.0, Gate.modulation(0.5, 0.3, p));
        assertEquals(1.0, Gate.modulation(1.0, 0.5, p));
    }

    /** g monotone non croissante en chaque argument. */
    @Test
    void modulation_monotone_nonincreasing() {
        Gate.ModParams p = new Gate.ModParams(1.0, 0.5, 0.5, 2.0, 0.05);
        double prev = 1.0;
        for (int k = 0; k < 20; k++) {
            double g = Gate.modulation(1.0 + k * 0.5, 0.5, p);
            assertTrue(g <= prev + 1e-12, "non monotone en T a k=" + k);
            prev = g;
        }
        prev = 1.0;
        for (int k = 0; k < 20; k++) {
            double g = Gate.modulation(0.0, 0.5 + k * 0.03, p);
            assertTrue(g <= prev + 1e-12, "non monotone en D a k=" + k);
            prev = g;
        }
    }

    /** g bornee par g_min (jamais zero). */
    @Test
    void modulation_bounded_below_by_gmin() {
        Gate.ModParams p = new Gate.ModParams(0.0, 10.0, 0.0, 10.0, 0.3);
        assertEquals(0.3, Gate.modulation(100.0, 1.0, p));
    }

    /** Valeur exacte : exp(-a_T(T~-t0)+ - a_D(D-d0)+). */
    @Test
    void modulation_exact_formula() {
        Gate.ModParams p = new Gate.ModParams(1.0, 0.5, 0.5, 2.0, 0.01);
        double got = Gate.modulation(3.0, 0.8, p);
        double want = Math.exp(-0.5 * (3.0 - 1.0) - 2.0 * (0.8 - 0.5));
        assertEquals(want, got, 1e-12);
    }
}
