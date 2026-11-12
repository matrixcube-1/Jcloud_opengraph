package opengraph.validation;

import static org.junit.jupiter.api.Assertions.*;

import java.util.ArrayList;
import java.util.List;
import opengraph.core.Engine;
import opengraph.core.ExecOutcome;
import opengraph.core.ExecParams;
import opengraph.core.ExtPathTables;
import opengraph.core.PathTables;
import opengraph.core.PathsEvalTest;
import opengraph.core.SymbolSeries;
import opengraph.core.Universe;
import org.junit.jupiter.api.Test;

/** Monte-Carlo E9 + preuves Annexe H. */
public class ValidationTest {

    @Test
    public void proofChecksPass() {
        assertTrue(ProofChecks.run(PathTables.build(),
                ExtPathTables.build(PathTables.build())).isEmpty());
    }

    private Engine book() {
        double[] ups = PathsEvalTest.ups();
        List<SymbolSeries> series = new ArrayList<>(
                PathsEvalTest.book(ups, 1e-4,
                        new String[] {"CADJPY", "1.001"}));
        return new Engine(series);
    }

    @Test
    public void mcDeterministicSeed() {
        Engine eng = book();
        PathTables pt = PathTables.build();
        int pi = 0;
        // un chemin qui s'execute dans ce livre
        ExecOutcome probe = eng.execSeq(1_000, pi,
                new ExecParams(2.5, 0.8, 0.5, false), pt);
        assertNotNull(probe);
        MonteCarlo.Summary a = MonteCarlo.run(eng, 1_000, pi,
                ExecParams.doc(2.5, 0.8, 0.5, 500.0, 1.0, 3), pt,
                200, 0.9, 42L);
        MonteCarlo.Summary b = MonteCarlo.run(eng, 1_000, pi,
                ExecParams.doc(2.5, 0.8, 0.5, 500.0, 1.0, 3), pt,
                200, 0.9, 42L);
        assertEquals(a.done, b.done);
        assertEquals(a.died, b.died);
        assertEquals(a.meanRealizable(), b.meanRealizable(), 1e-15);
        assertEquals(200, a.trials);
        // S=0.9 -> ~10% meurent sans executer
        assertTrue(a.died > 0);
        assertTrue(a.done + a.aborted + a.died == 200);
    }

    @Test
    public void mcSurvivalZeroNeverExecutes() {
        Engine eng = book();
        PathTables pt = PathTables.build();
        MonteCarlo.Summary s = MonteCarlo.run(eng, 1_000, 0,
                ExecParams.doc(2.5, 0.8, 0.5, 500.0, 1.0, 3), pt,
                100, 0.0, 7L);
        assertEquals(0, s.done);
        assertEquals(100, s.died);
        // un chemin non executable ne compte jamais comme un gain
        assertEquals(0.0, s.meanRealizable(), 1e-12);
    }
}
