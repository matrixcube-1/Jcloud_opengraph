package opengraph.validation;

import java.util.Random;
import opengraph.core.Engine;
import opengraph.core.ExecOutcome;
import opengraph.core.ExecParams;
import opengraph.core.PathTables;

/**
 * Monte-Carlo E9 : simulation fill-par-fill conditionnee a la survie.
 * Un chemin non executable dans sa duree de vie predite ne compte pas
 * comme un gain : un tirage ou la survie echoue est un abort simule,
 * jamais un pnl execute. Latence jitteree U(0.5, 1.5) x latMs.
 */
public final class MonteCarlo {

    public static final class Summary {
        public int trials, done, aborted, died;
        public double pnlSum, pnlRealizableSum;

        public double meanPnl() {
            return trials > 0 ? pnlSum / trials : 0.0;
        }

        /** Pnl moyen ne comptant que les chemins reellement executables. */
        public double meanRealizable() {
            int n = done + aborted;
            return n > 0 ? pnlRealizableSum / n : 0.0;
        }
    }

    /**
     * survivalS = S(T_seq) de Cox pour ce candidat (0..1). rng seede ->
     * deterministe. ep.docMode est honore (E1-E9 reelles).
     */
    public static Summary run(Engine eng, long t0, int pi, ExecParams ep,
                              PathTables pt, int trials, double survivalS,
                              long seed) {
        Summary s = new Summary();
        Random rng = new Random(seed);
        for (int i = 0; i < trials; i++) {
            // le desequilibre meurt-il avant la fin de la sequence ?
            if (rng.nextDouble() > survivalS) {
                s.died++;           // chemin non executable : pas un gain
                s.trials++;
                continue;
            }
            ExecParams jit = new ExecParams(
                    ep.latMs * (0.5 + rng.nextDouble()), ep.tauSlipBp,
                    ep.qMin, ep.volGuard, ep.staleMs, ep.spreadCapMult,
                    ep.mAbortUsd, ep.nU, ep.docMode);
            ExecOutcome r = eng.execSeq(t0, pi, jit, pt);
            if (r == null) {
                s.died++;
            } else if (r instanceof ExecOutcome.Done) {
                s.done++;
                s.pnlRealizableSum += r.pnl();
            } else {
                s.aborted++;
                s.pnlRealizableSum += r.pnl();
            }
            s.trials++;
        }
        s.pnlSum = s.pnlRealizableSum;
        return s;
    }
}
