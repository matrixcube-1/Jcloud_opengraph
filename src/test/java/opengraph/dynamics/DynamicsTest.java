package opengraph.dynamics;

import static org.junit.jupiter.api.Assertions.*;

import java.util.Random;
import org.junit.jupiter.api.Test;

/**
 * Couche dynamique §10 du document scelle : Hawkes, Cox, Hurst, BIR,
 * p_persist. Premiere implementation executable (spec jamais codee).
 */
public class DynamicsTest {

    // ---------- HawkesMLE ----------

    @Test
    public void hawkesFitsClusteredProcess() {
        // processus fortement auto-excite : salves d'evenements
        double[] ts = new double[400];
        int k = 0;
        Random rng = new Random(42);
        double t = 0.0;
        while (k < 400) {
            t += 200.0 + rng.nextDouble() * 400.0; // mere ~ toutes 400 ms
            int burst = 2 + rng.nextInt(5);        // + 2-6 filles
            for (int j = 0; j < burst && k < 400; j++) {
                ts[k++] = t + rng.nextDouble() * 30.0;
            }
        }
        HawkesMLE.Fit f = HawkesMLE.fit(ts, ts[399] + 500.0);
        assertTrue(f.mu > 0.0);
        assertTrue(f.alpha >= 0.0 && f.beta > 0.0);
        assertTrue(f.branching() < 1.0 || f.alpha > 0.0);
        // un processus en salves doit avoir une excitation mesurable
        assertTrue(f.alpha > 0.01, "alpha=" + f.alpha);
    }

    @Test
    public void hawkesPoissonHasNoExcitation() {
        // evenements quasi-reguliers -> alpha ~ 0
        double[] ts = new double[200];
        for (int i = 0; i < 200; i++) {
            ts[i] = i * 100.0;
        }
        HawkesMLE.Fit f = HawkesMLE.fit(ts, 20_000.0);
        assertTrue(f.alpha < 0.5, "alpha=" + f.alpha);
    }

    // ---------- Cox ----------

    @Test
    public void coxSurvivalClosedForm() {
        // S(h) = exp(-mu*h - (a/b)*B*(1-exp(-b*h)))
        double mu = 0.001, a = 0.5, b = 0.02;
        CoxSurvival cox = new CoxSurvival(mu, a, b);
        assertEquals(1.0, cox.s(0.0, 0.0), 1e-12);
        // sans excitation : S(h) = exp(-mu*h)
        assertEquals(Math.exp(-mu * 1000.0), cox.s(1000.0, 0.0), 1e-12);
        // avec excitation B=1, h=100 : integral = mu*100 + (a/b)*1*(1-e^-2)
        double expect = Math.exp(-mu * 100.0
                - (a / b) * 1.0 * (1.0 - Math.exp(-b * 100.0)));
        assertEquals(expect, cox.s(100.0, 1.0), 1e-12);
        // monotone decroissante en h
        assertTrue(cox.s(500.0, 0.5) < cox.s(100.0, 0.5));
    }

    @Test
    public void coxStateUpdates() {
        CoxSurvival cox = new CoxSurvival(0.0, 1.0, 0.01);
        double b = cox.excite(0.0, 1000.0); // dt=1000, un choc
        // B = 0*e^-10 + alpha = 1.0 (nouveau choc contribue alpha)
        assertEquals(1.0, b, 1e-12);
        b = cox.excite(1.0, 1000.0); // B=1, dt=1000 -> e^-10 + 1
        assertEquals(1.0 + Math.exp(-10.0), b, 1e-9);
    }

    // ---------- Hurst ----------

    @Test
    public void hurstBrownianNearHalf() {
        Random rng = new Random(7);
        double[] r = new double[8192];
        for (int i = 0; i < r.length; i++) {
            r[i] = rng.nextGaussian();
        }
        double h = Hurst.estimate(r);
        assertEquals(0.5, h, 0.10, "H=" + h);
    }

    @Test
    public void hurstPersistentAbove() {
        // serie a increments positivement correles (marche tendanciel)
        Random rng = new Random(8);
        double[] r = new double[8192];
        double m = 0.0;
        for (int i = 0; i < r.length; i++) {
            m = 0.8 * m + 0.6 * rng.nextGaussian();
            r[i] = m;
        }
        double h = Hurst.estimate(r);
        assertTrue(h > 0.55, "H=" + h);
    }

    @Test
    public void hurstMeanRevertingBelow() {
        // increments alternes -> H < 0.5
        Random rng = new Random(9);
        double[] r = new double[8192];
        double s = 1.0;
        for (int i = 0; i < r.length; i++) {
            r[i] = s * (0.3 + 0.4 * rng.nextDouble());
            s = -s;
        }
        double h = Hurst.estimate(r);
        assertTrue(h < 0.5, "H=" + h);
    }

    // ---------- Shocks ----------

    @Test
    public void shocksDetectJump() {
        // mid stable puis un saut > 2 sigma
        long[] ts = new long[300];
        double[] mid = new double[300];
        for (int i = 0; i < 300; i++) {
            ts[i] = i * 100L;
            mid[i] = 1.10 + (i % 2) * 1e-5; // micro bruit deterministe
        }
        for (int i = 200; i < 300; i++) {
            mid[i] = 1.12; // saut permanent ~180 bp
        }
        long[] shocks = Shocks.detect(ts, mid, 50, 2.0);
        assertEquals(1, shocks.length);
        assertEquals(ts[200], shocks[0]);
    }

    // ---------- BIR ----------

    @Test
    public void birFormula() {
        // (Vb-Va)/(Vb+Va) x exp(-gamma*spread/mid), gamma=10
        double bir = BIR.compute(2.0, 1.0, 1e-4, 1.10);
        assertEquals((1.0 / 3.0) * Math.exp(-10.0 * 1e-4 / 1.10), bir, 1e-12);
        assertEquals(0.0, BIR.compute(1.0, 1.0, 1e-4, 1.10));
        // desequilibre vendeur -> negatif -> tronque a 0 dans p_persist
        assertTrue(BIR.compute(1.0, 3.0, 1e-4, 1.10) < 0.0);
    }

    // ---------- EpisodeTracker ----------

    @Test
    public void episodesOpenCloseMaxNet() {
        EpisodeTracker tr = new EpisodeTracker(0.27);
        assertTrue(tr.feed(1000, 0.5, 7).isEmpty());   // ouvre path 7
        assertTrue(tr.feed(1100, 0.9, 7).isEmpty());   // maj maxNet
        assertTrue(tr.feed(1200, 0.4, 9).isEmpty());   // ouvre path 9
        assertEquals(2, tr.openCount());
        var closed = tr.feed(1300, -1.0, 0);            // signal tombe
        assertEquals(2, closed.size());
        assertEquals(0, tr.openCount());
        EpisodeTracker.Episode e7 = closed.stream()
                .filter(e -> e.path == 7).findFirst().get();
        assertEquals(1000, e7.tOpen);
        assertEquals(1300, e7.tClose);
        assertEquals(0.9, e7.maxNet, 1e-12);
        assertTrue(tr.feed(1400, Double.NaN, 0).isEmpty()); // rien a fermer
    }

    // ---------- PPersist ----------

    @Test
    public void pPersistGates() {
        PPersist gate = new PPersist(0.30);
        // S=0.8, H=0.6>0.55, BIR_adj=0.5 -> p=0.4 >= 0.30 -> admis
        assertTrue(gate.admit(0.8, 0.6, 0.5));
        assertEquals(0.4, gate.p(0.8, 0.6, 0.5), 1e-12);
        // H trop faible -> 0
        assertEquals(0.0, gate.p(0.8, 0.50, 0.5));
        assertFalse(gate.admit(0.8, 0.50, 0.5));
        // BIR negatif -> tronque
        assertEquals(0.0, gate.p(0.8, 0.6, -0.3));
        // S trop faible -> sous seuil
        assertFalse(gate.admit(0.2, 0.6, 0.9));
    }
}
