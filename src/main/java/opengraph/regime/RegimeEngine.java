package opengraph.regime;

/**
 * Orchestrateur boucle lente — port de regime_indicators::engine.rs.
 *
 * A chaque pas de boucle lente (temps evenementiel, jamais l'horloge
 * murale) : mids -> x -> p -> R -> A -> T brut -> EWMA -> z robuste (T~),
 * y = p - p_prev -> gramien glissant -> D, c* -> porte -> instantane.
 * En rejeu : fonction deterministe des donnees.
 */
public final class RegimeEngine {

    /** Parametres de la boucle lente. */
    public static class RegimeParams {
        /** Demi-vie EWMA de la tension (ns evenementiels). */
        public double hTNs = 500.0;
        /** Taille de l'historique de z (valeurs lissees). */
        public int wT = 20;
        /** Minimum d'historique avant z disponible. */
        public int wMin = 4;
        /** Plancher du denominateur MAD. */
        public double mMin = 1e-9;
        /** Fenetre des rendements panier pour la SVD. */
        public int wReturns = 10;
        /** Recalcul exact du gramien tous les K echantillons. */
        public int gramRecomputeEvery = 50;
        /** q_min pour c* dans [1/8, 7/8]. */
        public double qSvdMin = 0.5;
        /** L (nombre de sauts) — structurel : 3 (limite de jambes). */
        public int lHops = 3;
        /** Garde technique normalisation. */
        public double eps = 1e-12;
        public Gate.GateParams gate = new Gate.GateParams(
                3.0, 0.5, 0.95, 0.02, 3_000);
    }

    public final RegimeParams p;
    private final double[][] edges;
    private double[] prevP;
    private final Smoothing.Ewma ewmaT;
    private final Smoothing.RobustZ zT;
    private final SvdRegime.RollingGram gram;
    private final Gate gate;

    /** edges : paires cotees de la venue (symetrique, K8 pour IC Markets). */
    public RegimeEngine(RegimeParams p, double[][] edges) {
        this.p = p;
        this.edges = edges;
        this.ewmaT = new Smoothing.Ewma(p.hTNs);
        this.zT = new Smoothing.RobustZ(p.wT, p.wMin, p.mMin);
        this.gram = new SvdRegime.RollingGram(p.wReturns,
                p.gramRecomputeEvery);
        this.gate = new Gate(p.gate);
        this.prevP = null;
    }

    /**
     * Un pas : x = log-taux mids antisym., sNorm = demi-spreads normaux.
     * Retourne l'instantane publie (gate eventuellement fermee —
     * fail-closed : tension/dominance NaN).
     */
    public RegimeSnapshot step(long tNs, double[][] x, double[][] sNorm) {
        double tTilde = Double.NaN;
        double dominance = Double.NaN;
        int dominant = -1;
        int direction = 0;

        double[] pr = Tension.potential(x, edges);
        if (pr != null) {
            // Tension : R -> A -> T brut -> EWMA -> z robuste
            double[][] r = LinAlg.fromFn((i, j) ->
                    edges[i][j] > 0.0 ? x[i][j] - (pr[i] - pr[j]) : 0.0);
            double[][] a = Tension.adjacency(r, sNorm, edges);
            double tRaw = Tension.tensionFromAdjacency(a, p.lHops, p.eps);
            double tSmooth = ewmaT.update(tNs, tRaw);
            tTilde = zT.update(tSmooth);
            // Dominance : y = p - p_prev -> gramien
            if (prevP != null) {
                double[] y = new double[8];
                for (int i = 0; i < 8; i++) {
                    y[i] = pr[i] - prevP[i];
                }
                gram.push(y);
                SvdRegime sr = gram.dominance(p.qSvdMin);
                if (sr != null) {
                    dominance = sr.dominance;
                    dominant = sr.dominant;
                    direction = sr.direction;
                }
            }
            prevP = pr;
        }

        boolean gateOpen = gate.update(tNs, tTilde, dominance);
        return new RegimeSnapshot(0, tNs, gateOpen, tTilde, dominance,
                dominant, direction);
    }
}
