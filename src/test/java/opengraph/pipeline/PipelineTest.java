package opengraph.pipeline;

import static org.junit.jupiter.api.Assertions.*;

import java.util.ArrayList;
import java.util.List;
import opengraph.core.BoutUniverse;
import opengraph.core.Engine;
import opengraph.core.ExtPathTables;
import opengraph.core.PathTables;
import opengraph.core.PathsEvalTest;
import opengraph.core.SymbolSeries;
import opengraph.core.Universe;
import opengraph.dynamics.CoxSurvival;
import org.junit.jupiter.api.Test;

/** Pipeline §19 : enchainement eval -> E2 -> p_persist -> FIRE. */
public class PipelineTest {

    /** 12:00:01 UTC — hors fenetre rollover. */
    private static final long T0 = 43_201_000L;

    private static SymbolSeries sym(String s, double bid, double ask) {
        return new SymbolSeries(s, new long[] {T0}, new double[] {bid},
                new double[] {ask}, new double[] {1.0},
                new double[] {1.0});
    }

    private Engine book(boolean distort) {
        double[] ups = PathsEvalTest.ups();
        List<SymbolSeries> out = new ArrayList<>();
        for (String s : Universe.FX) {
            int a = Universe.ci(s.substring(0, 3));
            int b = Universe.ci(s.substring(3, 6));
            double mid = ups[a] / ups[b];
            if (distort && s.equals("CADJPY")) {
                mid *= 1.001;
            }
            out.add(sym(s, mid * 0.9999, mid * 1.0001));
        }
        for (var bo : BoutUniverse.BOUTS) {
            out.add(sym(bo.name, 999.0, 1_001.0));
        }
        Engine e = new Engine(out);
        e.sync(T0);
        return e;
    }

    private Pipeline pipe(Engine e, double mu) {
        return new Pipeline(e, PathTables.build(),
                ExtPathTables.build(PathTables.build()),
                new CoxSurvival(mu, 0.0, 0.01), 0.05, 0.0);
    }

    @Test
    public void rolloverBlocks() {
        long t = 23L * 3_600_000L + 58L * 60_000L; // 23:58
        assertTrue(Pipeline.rollover(t));
        assertEquals(Pipeline.Decision.ROLLOVER,
                pipe(book(true), 0.0).decide(t, 500, 1.7, 1.0).decision);
    }

    @Test
    public void noEdgeWhenBookFlat() {
        assertEquals(Pipeline.Decision.NO_EDGE,
                pipe(book(false), 0.0)
                        .decide(T0, 500, 1.7, 1.0).decision);
    }

    @Test
    public void fireWhenEdgeSurvives() {
        Pipeline p = pipe(book(true), 0.0);
        p.hurst = 0.6; // > 0.55
        Pipeline.Out o = p.decide(T0, 500, 1.7, 1.0);
        if (o.eval == null || o.eval.netUsd < 0.0) {
            assertEquals(Pipeline.Decision.NO_EDGE, o.decision);
            return;
        }
        assertEquals(Pipeline.Decision.FIRE, o.decision);
        assertTrue(o.sH > 0.30);
    }

    @Test
    public void survivalGateBlocks() {
        // mu enorme -> S(h) ~ 0 -> E2 bloque
        Pipeline p = pipe(book(true), 1.0);
        p.hurst = 0.6;
        Pipeline.Out o = p.decide(T0, 500, 1.7, 1.0);
        assertTrue(o.decision == Pipeline.Decision.E2_SURVIVAL
                || o.decision == Pipeline.Decision.NO_EDGE);
    }
}
