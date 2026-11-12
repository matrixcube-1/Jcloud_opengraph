package opengraph.core;

/** Issue d'une sequence d'execution (exec.rs::ExecOutcome). */
public abstract class ExecOutcome {

    /** Sequence terminee. */
    public static final class Done extends ExecOutcome {
        public final double pnlUsd;
        public final int filledLegs;
        public final int partialLegs;
        public final double durMs;

        public Done(double pnlUsd, int filledLegs, int partialLegs,
                    double durMs) {
            this.pnlUsd = pnlUsd;
            this.filledLegs = filledLegs;
            this.partialLegs = partialLegs;
            this.durMs = durMs;
        }
    }

    /** Sequence abandonnee en cours de route. */
    public static final class Abort extends ExecOutcome {
        public final AbortReason reason;
        public final double pnlUsd;
        public final int filledLegs;
        public final int partialLegs;
        public final double durMs;
        /** E7 : residuel non liquide apres N_u echecs de unwind (USD). */
        public final double residUsd;

        public Abort(AbortReason reason, double pnlUsd, int filledLegs,
                     int partialLegs, double durMs) {
            this(reason, pnlUsd, filledLegs, partialLegs, durMs, 0.0);
        }

        public Abort(AbortReason reason, double pnlUsd, int filledLegs,
                     int partialLegs, double durMs, double residUsd) {
            this.reason = reason;
            this.pnlUsd = pnlUsd;
            this.filledLegs = filledLegs;
            this.partialLegs = partialLegs;
            this.durMs = durMs;
            this.residUsd = residUsd;
        }
    }

    public double pnl() {
        if (this instanceof Done) {
            return ((Done) this).pnlUsd;
        }
        return ((Abort) this).pnlUsd;
    }

    public double durMs() {
        if (this instanceof Done) {
            return ((Done) this).durMs;
        }
        return ((Abort) this).durMs;
    }
}
