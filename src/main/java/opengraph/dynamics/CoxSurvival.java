package opengraph.dynamics;

/**
 * Survie du desequilibre sous intensite Hawkes (§10, modele de Cox) :
 *   S(h) = exp(-integrale_0^h lambda(t+u) du)
 *        = exp(-mu*h - (alpha/beta) * B * (1 - exp(-beta*h)))
 * ou B = sum_i alpha * exp(-beta*(t - t_i)) est l'excitation courante,
 * maintenue incrementalement : B <- B*e^(-beta*dt) + alpha a chaque choc.
 * Unites : mu, alpha, beta en par-ms ; h en ms.
 */
public final class CoxSurvival {

    public final double mu, alpha, beta;

    public CoxSurvival(double mu, double alpha, double beta) {
        this.mu = mu;
        this.alpha = alpha;
        this.beta = beta;
    }

    /** S(h) a horizon h ms, excitation courante B (en unites alpha). */
    public double s(double hMs, double excitationB) {
        if (hMs <= 0.0) {
            return 1.0;
        }
        double integ = mu * hMs
                + (beta > 0.0
                        ? (alpha / beta) * excitationB
                                * (1.0 - Math.exp(-beta * hMs))
                        : 0.0);
        return Math.exp(-integ);
    }

    /**
     * Mise a jour de l'excitation apres un nouveau choc :
     * B <- B * exp(-beta*dtMs) + alpha.
     */
    public double excite(double prevB, double dtMs) {
        return prevB * Math.exp(-beta * dtMs) + alpha;
    }

    /** Decroissance pure sans nouveau choc : B <- B * exp(-beta*dtMs). */
    public double decay(double prevB, double dtMs) {
        return prevB * Math.exp(-beta * dtMs);
    }
}
