package opengraph.core;

/**
 * Instrument terminal (« bout ») — Annexe J.3, univers operationnel.
 * Un bout est un puits du graphe : position detenue dans sa devise de
 * cotation, liquidee en USD sous Convention A (bid/ask executables).
 */
public final class Bout {

    public enum Cls { INDEX, METAL, ENERGY }
    public enum Tier { S, L, X }

    public final String name;
    /** Indice de la devise de cotation (Universe.ci). */
    public final int qc;
    public final Cls cls;
    public final Tier tier;
    /** Taux de marge (fraction du nominal, ex. 0.05 = 1:20). */
    public final double marginRate;
    /** Commission par lot et par cote, en USD. */
    public final double commUsdPerLotSide;
    /** Unites d'instrument par lot (XAU=100 oz, XAG=5000, indice=1). */
    public final double contractSize;
    /** Pas d'arrondi de quantite (unites). */
    public final double lotStep;
    /** Symbole JForex ("XAU/USD", "USA500.IDX/USD" — a valider en demo). */
    public final String jfx;

    public Bout(String name, int qc, Cls cls, Tier tier, double marginRate,
                double commUsdPerLotSide, double contractSize,
                double lotStep, String jfx) {
        this.name = name;
        this.qc = qc;
        this.cls = cls;
        this.tier = tier;
        this.marginRate = marginRate;
        this.commUsdPerLotSide = commUsdPerLotSide;
        this.contractSize = contractSize;
        this.lotStep = lotStep;
        this.jfx = jfx;
    }
}
