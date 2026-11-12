package opengraph.sizing;

import static org.junit.jupiter.api.Assertions.*;

import org.junit.jupiter.api.Test;

/**
 * Couche sizing LP (remplacement interne de HiGHS, document §10/§19) :
 * max sum d_j*S_j*x_j sous bornes Kelly, marge rho_min, concentration,
 * puis arrondi granularite + re-check regle 21.
 */
public class SizingTest {

    // ---------- Simplex ----------

    @Test
    public void simplexKnownOptimum() {
        // max x + y ; x + 2y <= 4 ; x <= 2 ; x,y >= 0  ->  x=2, y=1, obj=3
        double[] c = {1.0, 1.0};
        double[][] A = {{1.0, 2.0}, {1.0, 0.0}};
        double[] b = {4.0, 2.0};
        Simplex.Result r = Simplex.maximize(c, A, b);
        assertTrue(r.optimal);
        assertEquals(3.0, r.objective, 1e-9);
        assertEquals(2.0, r.x[0], 1e-9);
        assertEquals(1.0, r.x[1], 1e-9);
    }

    @Test
    public void simplexRespectsUpperBoundRow() {
        // max 3x ; x <= 5 -> obj 15
        Simplex.Result r = Simplex.maximize(new double[] {3.0},
                new double[][] {{1.0}}, new double[] {5.0});
        assertTrue(r.optimal);
        assertEquals(15.0, r.objective, 1e-9);
    }

    @Test
    public void simplexInfeasible() {
        // x >= 2 exprime en -x <= -2... avec b<0 -> infaisable de base ;
        // notre solveur n'accepte que b>=0 : tester plutot une contrainte
        // impossible x <= -1 (b negatif) -> non-optimal.
        Simplex.Result r = Simplex.maximize(new double[] {1.0},
                new double[][] {{-1.0}}, new double[] {-2.0});
        assertFalse(r.optimal);
    }

    // ---------- Sizing ----------

    private static Sizing.Cand cand(double d, double s, double marginRate,
                                    double kellyUsd) {
        Sizing.Cand c = new Sizing.Cand();
        c.dUsd = d;
        c.survival = s;
        c.marginRate = marginRate;
        c.kellyCapUsd = kellyUsd;
        c.notionalUsd = 10_000.0;
        c.stepUsd = 1.0;
        return c;
    }

    @Test
    public void sizingAllocatesToBestSurvival() {
        // budget marge ne permet qu'un candidat -> tout sur S maximal
        Sizing.Cand[] cs = {cand(5.0, 0.4, 0.05, 1e9),
                cand(5.0, 0.9, 0.05, 1e9)};
        Sizing s = new Sizing(10_000.0, 3.0, 10.0);
        double[] x = s.allocate(cs);
        assertNotNull(x);
        assertEquals(0.0, x[0], 1e-9);
        assertTrue(x[1] > 0.0);
        // marge : 0.05 * x_total <= equity/rho = 3 333
        double margin = 0.05 * (x[0] * 10_000 + x[1] * 10_000);
        assertTrue(margin <= 10_000.0 / 3.0 + 1e-6, "margin=" + margin);
    }

    @Test
    public void sizingRespectsKellyCap() {
        Sizing.Cand[] cs = {cand(5.0, 0.9, 0.05, 20_000.0)};
        Sizing s = new Sizing(1e9, 1.0, 1.0); // marge non liante
        double[] x = s.allocate(cs);
        assertNotNull(x);
        assertTrue(x[0] * 10_000.0 <= 20_000.0 + 1e-9);
    }

    @Test
    public void sizingSkipsNonPositiveEdge() {
        Sizing.Cand[] cs = {cand(-1.0, 0.9, 0.05, 1e9),
                cand(0.0, 0.9, 0.05, 1e9)};
        Sizing s = new Sizing(100_000.0, 3.0, 0.5);
        double[] x = s.allocate(cs);
        assertTrue(x == null || (x[0] == 0.0 && x[1] == 0.0));
    }

    @Test
    public void sizingRoundsDownToStep() {
        Sizing.Cand[] cs = {cand(5.0, 0.9, 0.05, 1e9)};
        cs[0].stepUsd = 7_000.0;
        Sizing s = new Sizing(100_000.0, 3.0, 1.0);
        double[] x = s.allocate(cs);
        assertNotNull(x);
        double usd = x[0] * 10_000.0;
        assertEquals(0.0, usd % 7_000.0, 1e-6);
        // re-check : marge toujours respectee apres arrondi
        assertTrue(0.05 * usd <= 100_000.0 / 3.0 + 1e-6);
    }
}
