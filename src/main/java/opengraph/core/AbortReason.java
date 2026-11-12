package opengraph.core;

/** Cause d'abandon d'une sequence (exec.rs::AbortReason). */
public enum AbortReason {
    NO_QUOTE,
    KILL_IOC,
    BELOW_QMIN,
    /** E3a : quote trop vieille au moment du fill (docMode). */
    STALE_QUOTE,
    /** E3b : spread > 1.7x normal (docMode). */
    SPREAD_WIDE,
    /** E4 : E[net residuel] < -(C_unwind + m_abort). */
    E4_RESIDUAL
}
