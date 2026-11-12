package opengraph.strategy;

import com.dukascopy.api.Configurable;
import com.dukascopy.api.IAccount;
import com.dukascopy.api.IBar;
import com.dukascopy.api.IConsole;
import com.dukascopy.api.IContext;
import com.dukascopy.api.IEngine;
import com.dukascopy.api.IEngine.OrderCommand;
import com.dukascopy.api.IMessage;
import com.dukascopy.api.IOrder;
import com.dukascopy.api.IStrategy;
import com.dukascopy.api.ITick;
import com.dukascopy.api.Instrument;
import com.dukascopy.api.JFException;
import com.dukascopy.api.Period;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import opengraph.core.BoutUniverse;
import opengraph.core.Engine;
import opengraph.core.EvalResult;
import opengraph.core.ExtPathTables;
import opengraph.core.Fees;
import opengraph.core.PathTables;
import opengraph.core.Quote;
import opengraph.core.Universe;
import opengraph.dynamics.CoxSurvival;
import opengraph.dynamics.DynamicsProbe;
import opengraph.pipeline.Pipeline;

/**
 * Strategie JForex — adaptateur venue du moteur opengraph.
 *
 * Parite avec le modele de reference Rust (engine exec_seq) :
 * - univers 28 FX, chemins ouverts 1-3 jambes (2 072 chemins) ;
 * - panier complet [USD->s0] + jambes + [end->USD] ;
 * - quantites CHAINEES sur les fills reels (getFilledAmount x
 *   getOpenPrice), jamais le nominal theorique ;
 * - ordres marche avec slippage max en pips = IOC a portee tau_slip_bp ;
 * - fill partiel < qMin -> abort + unwind immediat des jambes remplies ;
 * - debouclage via order.close() (montant = quantite remplie) ;
 * - settlement par engine.execSettle — meme arithmetique que la sim ;
 * - breaker : breakerFails echecs consecutifs -> halt ;
 * - warm-up : aucun eval avant warmupTicks ticks par symbole ;
 * - allowLive=false par defaut : la strategie evalue et journalise mais
 *   refuse de soumettre le moindre ordre (detect-only, defense en
 *   profondeur — a basculer explicitement par l'operateur).
 *
 * Execution sequentielle : runSequence() bloque sur waitForUpdate
 * (FILLED/CANCELED) comme exec_seq — un ordre a la fois, jamais de jambe
 * sans la precedente remplie.
 */
public class OgStrategy implements IStrategy {

    // ---------------- parametres (@Configurable = panneau JForex) -------

    @Configurable("AllowLive (ordres reels)")
    public boolean allowLive = false;
    @Configurable("Fresh ms")
    public double freshMs = 8_000.0;
    @Configurable("Spread cap x mediane")
    public double spreadCap = 1.76;
    @Configurable("Comm scale")
    public double commScale = 1.10;
    @Configurable("DDD min USD")
    public double dddMin = 0.27;
    @Configurable("Tau slip bp (IOC)")
    public double tauSlipBp = 0.69;
    @Configurable("Q min (fill partiel)")
    public double qMin = 0.5;
    @Configurable("Min seq gap ms")
    public long minSeqGapMs = 2_000;
    @Configurable("Leg timeout ms")
    public long legTimeoutMs = 2_000;
    @Configurable("Breaker fails")
    public int breakerFails = 5;
    @Configurable("Warmup ticks/sym")
    public int warmupTicks = 100;
    @Configurable("Modele doc E1-E9+§10")
    public boolean docMode = false;
    @Configurable("Hawkes mu (par ms)")
    public double hawkMu = 0.0;
    @Configurable("Hawkes alpha (par ms)")
    public double hawkAlpha = 0.0;
    @Configurable("Hawkes beta (par ms)")
    public double hawkBeta = 0.01;
    @Configurable("Tau p_persist")
    public double tauPersist = 0.05;
    @Configurable("M seq (T_seq mult)")
    public double mSeq = 1.5;

    // ---------------- etat interne --------------------------------------

    private Engine eng;
    private PathTables pt;
    private ExtPathTables xt;
    private Pipeline pipe;
    /** Une sonde par serie (jamais de melange inter-instruments, F1). */
    private DynamicsProbe[] probes;
    private IEngine jEngine;
    private IConsole console;
    private final Instrument[] instr = new Instrument[Universe.FX.length];
    private final int[] seen = new int[Universe.FX.length];
    private long lastSeqEnd = Long.MIN_VALUE;
    private int consecFail;
    private boolean halted;
    private boolean inSeq;
    private long seqCounter;

    /** Un fill observe : fraction + prix d'ouverture (contrat execSettle). */
    private static final class Fill {
        final double frac;
        final double px;
        final IOrder order;

        Fill(double frac, double px, IOrder order) {
            this.frac = frac;
            this.px = px;
            this.order = order;
        }
    }

    // ---------------- cycle de vie ---------------------------------------

    @Override
    public void onStart(IContext context) throws JFException {
        jEngine = context.getEngine();
        console = context.getConsole();
        eng = Engine.live();
        pt = PathTables.build();
        xt = ExtPathTables.build(pt);
        CoxSurvival cox = new CoxSurvival(hawkMu, hawkAlpha, hawkBeta);
        pipe = new Pipeline(eng, pt, xt, cox, tauPersist, dddMin);
        pipe.mSeq = mSeq;
        probes = new DynamicsProbe[eng.sidx.size()];
        for (int i = 0; i < probes.length; i++) {
            probes[i] = new DynamicsProbe(2048, cox);
        }
        // la sonde lue = instrument d'entree du chemin gagnant (F1)
        pipe.onBest = (ev, isBout) -> {
            int fp = isBout ? xt.fxPi[ev.pathIdx] : ev.pathIdx;
            DynamicsProbe p = probes[eng.fxSi[pt.pE[fp][0]]];
            pipe.hurst = p.hurst();
            pipe.excitationB = p.excitation();
        };
        Set<Instrument> sub = new HashSet<>();
        for (int e = 0; e < Universe.FX.length; e++) {
            instr[e] = Instrument.fromString(
                    Universe.FX[e].substring(0, 3) + "/"
                            + Universe.FX[e].substring(3));
            if (instr[e] != null) {
                sub.add(instr[e]);
                instrMap.put(instr[e], e);
            }
        }
        StringBuilder miss = new StringBuilder();
        for (int e = 0; e < Universe.FX.length; e++) {
            if (instr[e] == null) {
                miss.append(Universe.FX[e]).append(' ');
            }
        }
        if (docMode) {
            for (int b = 0; b < BoutUniverse.BOUTS.length; b++) {
                if (!BoutUniverse.jfxTradable(b)) {
                    continue;
                }
                boutInstr[b] = Instrument.fromString(
                        BoutUniverse.BOUTS[b].jfx);
                if (boutInstr[b] != null) {
                    sub.add(boutInstr[b]);
                    boutMap.put(boutInstr[b], b);
                } else {
                    miss.append(BoutUniverse.BOUTS[b].jfx).append(' ');
                }
            }
        }
        context.setSubscribedInstruments(sub);
        log("OgStrategy demarre — " + sub.size() + " instruments souscrits, allowLive="
                + allowLive + " (detect-only tant que false)"
                + (miss.length() > 0 ? " | NON RESOLUS: " + miss : ""));
    }

    @Override
    public void onStop() throws JFException {
        flattenAll("onStop");
        log("OgStrategy arrete");
    }

    @Override
    public void onBar(Instrument instrument, Period period, IBar askBar,
                      IBar bidBar) throws JFException {
        // strategie tick-driven — rien ici
    }

    @Override
    public void onAccount(IAccount account) throws JFException {
        // equity surveillee par le venue — rien a faire
    }

    @Override
    public void onMessage(IMessage message) throws JFException {
        // les fills sont attendus via waitForUpdate dans runSequence ;
        // on journalise seulement les rejets hors-sequence
        if (message.getType() == IMessage.Type.ORDER_FILL_REJECTED
                || message.getType() == IMessage.Type.ORDER_CLOSE_REJECTED
                || message.getType() == IMessage.Type.ORDER_SUBMIT_REJECTED) {
            IOrder o = message.getOrder();
            log("REJET " + message.getType() + " order="
                    + (o == null ? "?" : o.getLabel()) + " reasons="
                    + message.getReasons());
        }
    }

    // ---------------- boucle tick ----------------------------------------

    @Override
    public void onTick(Instrument instrument, ITick tick)
            throws JFException {
        if (halted) {
            return;
        }
        long t = tick.getTime();
        int e = edgeOf(instrument);
        int si;
        if (e >= 0) {
            si = eng.fxSi[e];
            seen[e]++;
        } else if (docMode) {
            int b = boutOf(instrument);
            if (b < 0 || eng.boutSi[b] < 0) {
                return;
            }
            si = eng.boutSi[b];
        } else {
            return;
        }
        eng.ingest(si, new Quote(t, tick.getBid(), tick.getAsk(),
                Math.max(tick.getAskVolume(), tick.getBidVolume())));
        if (docMode) {
            probes[si].push(t, 0.5 * (tick.getBid() + tick.getAsk()));
        }
        if (inSeq || !warmed() || t < lastSeqEnd + minSeqGapMs) {
            return;
        }
        if (docMode) {
            Pipeline.Out o = pipe.decide(t, freshMs, spreadCap, commScale);
            if (o.decision != Pipeline.Decision.FIRE) {
                return;
            }
            log(String.format("OPP t=%d net=%.4f path=%d bout=%b S=%.3f",
                    t, o.eval.netUsd, o.eval.pathIdx, o.bout, o.sH));
            if (o.bout) {
                log("SKIP bout — execution FX-only pour l'instant");
                return;
            }
            runSequence(o.eval.pathIdx, t);
            return;
        }
        EvalResult ev = eng.evalAt(t, freshMs, spreadCap, commScale, pt);
        if (ev == null || ev.netUsd < dddMin) {
            return;
        }
        log(String.format("OPP t=%d net=%.4f path=%d", t, ev.netUsd,
                ev.pathIdx));
        runSequence(ev.pathIdx, t);
    }

    private boolean warmed() {
        for (int s : seen) {
            if (s < warmupTicks) {
                return false;
            }
        }
        return true;
    }

    private final Map<Instrument, Integer> instrMap = new HashMap<>();
    private final Map<Instrument, Integer> boutMap = new HashMap<>();

    private int edgeOf(Instrument i) {
        Integer e = instrMap.get(i);
        return e == null ? -1 : e;
    }

    private final Instrument[] boutInstr =
            new Instrument[BoutUniverse.BOUTS.length];

    private int boutOf(Instrument i) {
        Integer b = boutMap.get(i);
        return b == null ? -1 : b;
    }

    // ---------------- sequence d'execution (miroir exec_seq) -------------

    private void runSequence(int pi, long t) throws JFException {
        inSeq = true;
        seqCounter++;
        long seqId = seqCounter;
        List<Fill> fills = new ArrayList<>();
        try {
            int[][] orders = eng.ordersOf(pi, pt);
            if (orders == null) {
                log("seq" + seqId + " ABORT aretes manquantes");
                fail();
                return;
            }
            if (!allowLive) {
                log("seq" + seqId + " SKIP — allowLive=false");
                lastSeqEnd = t;
                return;
            }
            double qty = Fees.NOTIONAL_USD; // USD
            boolean aborted = false;
            for (int k = 0; k < orders.length; k++) {
                int e = orders[k][0];
                int from = orders[k][1];
                int si = eng.fxSi[e];
                Instrument ins = instr[e];
                double[] q = eng.quoteAt(si, t);
                if (q == null) {
                    log("seq" + seqId + " ABORT leg" + k + " quote absente");
                    aborted = true;
                    break;
                }
                int[] bn = Universe.pairNodes(e); // {base, quote}
                double bid = q[1];
                double ask = q[2];
                OrderCommand cmd;
                double amountBase;
                double pxRef;
                if (from == bn[0]) {
                    // vendre la base : SELL instrument, amount = qty (base)
                    cmd = OrderCommand.SELL;
                    amountBase = qty;
                    pxRef = bid;
                } else {
                    // vendre la quote : BUY instrument, amount = qty/ask
                    cmd = OrderCommand.BUY;
                    amountBase = qty / ask;
                    pxRef = ask;
                }
                // JForex : amount en millions ; pas minimal 1 unite
                // (0.000001) -> arrondi au pas pour eviter le rejet
                // "invalid amount precision"
                double amount = Math.round(amountBase) / 1e6;
                double pipSize = Math.pow(10, -ins.getPipScale());
                double slipPips = pxRef * tauSlipBp * 1e-4 / pipSize;
                String label = "OG" + seqId + "L" + k;
                IOrder o;
                try {
                    o = jEngine.submitOrder(label, ins, cmd, amount, 0,
                            slipPips);
                } catch (JFException ex) {
                    log("seq" + seqId + " ABORT leg" + k + " submit: "
                            + ex.getMessage());
                    aborted = true;
                    break;
                }
                o.waitForUpdate(legTimeoutMs, IOrder.State.FILLED,
                        IOrder.State.CANCELED);
                if (o.getState() != IOrder.State.FILLED) {
                    try {
                        o.close(); // annule un reste eventuel
                    } catch (JFException ignored) {}
                    log("seq" + seqId + " ABORT leg" + k + " etat="
                            + o.getState());
                    aborted = true;
                    break;
                }
                // getAmount() = montant rempli, getRequestedAmount() =
                // demande — les deux en millions d'unites de base
                double filledAmt = o.getAmount();
                double reqAmt = o.getRequestedAmount() > 0
                        ? o.getRequestedAmount() : amount;
                double frac = reqAmt > 0 ? filledAmt / reqAmt : 0.0;
                double filledUnits = filledAmt * 1e6; // unites de base
                double px = o.getOpenPrice();
                if (frac < qMin) {
                    fills.add(new Fill(frac, px, o));
                    try {
                        o.close();
                    } catch (JFException ignored) {}
                    log("seq" + seqId + " ABORT leg" + k + " partiel "
                            + String.format("%.3f", frac));
                    aborted = true;
                    break;
                }
                fills.add(new Fill(Math.min(frac, 1.0), px, o));
                // chainage : quantite recue dans la devise d'arrivee
                // SELL base -> recoit quote (filledUnits*px) ;
                // BUY base  -> recoit base (filledUnits)
                qty = cmd == OrderCommand.SELL
                        ? filledUnits * px : filledUnits;
            }
            double[][] f = new double[fills.size()][2];
            for (int i = 0; i < fills.size(); i++) {
                f[i][0] = fills.get(i).frac;
                f[i][1] = fills.get(i).px;
            }
            if (aborted) {
                unwind(fills, seqId);
                double[] res = eng.execSettle(pi, f, true, pt);
                log("seq" + seqId + " settle-abort "
                        + (res == null ? "?" : String.format(
                                "pnl=%.2f filled=%.0f", res[0], res[1])));
                fail();
            } else {
                unwind(fills, seqId); // fermeture du panier (aller-retour)
                double[] res = eng.execSettle(pi, f, false, pt);
                log("seq" + seqId + " settle "
                        + (res == null ? "?" : String.format(
                                "pnl=%.2f filled=%.0f resid=%.2f",
                                res[0], res[1], res[3])));
                consecFail = 0;
            }
            lastSeqEnd = t;
        } finally {
            inSeq = false;
        }
    }

    /**
     * Deboucle chaque jambe remplie (close par montant de fill). Un ordre
     * deja CLOSED (partiel abortif ferme en amont) est saute — sa position
     * est deja a plat, le second close() ne ferait que bruiter le journal.
     */
    private void unwind(List<Fill> fills, long seqId) {
        for (int i = fills.size() - 1; i >= 0; i--) {
            Fill fl = fills.get(i);
            if (fl.order.getState() == IOrder.State.CLOSED) {
                continue;
            }
            try {
                fl.order.close();
            } catch (JFException ex) {
                log("seq" + seqId + " unwind echec leg" + i + " : "
                        + ex.getMessage());
            }
        }
    }

    /** Filet de securite : ferme tout ordre ouvert du moteur. */
    private void flattenAll(String why) {
        try {
            for (int e = 0; e < instr.length; e++) {
                if (instr[e] == null) {
                    continue;
                }
                for (IOrder o : jEngine.getOrders(instr[e])) {
                    if (o.getState() == IOrder.State.FILLED
                            || o.getState() == IOrder.State.OPENED) {
                        try {
                            o.close();
                        } catch (JFException ignored) {}
                    }
                }
            }
        } catch (JFException ex) {
            log("flatten " + why + " : " + ex.getMessage());
        }
    }

    private void fail() {
        // le dernier gap est deja applique par l'appelant via lastSeqEnd
        consecFail++;
        if (consecFail >= breakerFails) {
            halted = true;
            log("BREAKER — " + consecFail + " echecs consecutifs, halt");
            flattenAll("breaker");
        }
    }

    private void log(String m) {
        if (console != null) {
            console.getOut().println(m);
        }
    }
}
