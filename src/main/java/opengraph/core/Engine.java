package opengraph.core;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Etat de marche forward-fill — port de engine.rs (Engine), eval.rs
 * (evalAt), exec.rs (execSeq/ordersOf/startQty/execSettle) et scan.rs
 * (scan/replay). Semantique panier complet Convention A.
 *
 * Invariants repris du POC :
 * - Curseur {@code pos} par symbole rembobinable : advance retombe sur une
 *   dichotomie quand {@code ts[pos] > t} (rejouabilite inter-trials).
 * - tick/sync ne publient que si {@code ts[j] <= t} — jamais de quote future.
 * - quoteAt est sans etat (dichotomie pure).
 */
public class Engine {

    /** Sentinelle "jamais vu" (-inf du POC). */
    public static final long NO_TICK = Long.MIN_VALUE;

    /** Symboles charges, tries (slist du POC). */
    public final List<String> syms = new ArrayList<>();
    /** sym -> indice dans syms/data/etat. */
    public final Map<String, Integer> sidx = new HashMap<>();
    public final List<SymbolSeries> data = new ArrayList<>();
    /** Curseur de lecture par symbole — rembobinable (primitif : pas de boxing). */
    public long[] pos = new long[0];
    /** Quote publiee courante (NaN si jamais vue). */
    public double[] bid;
    public double[] ask;
    /** Horodatage du dernier tick publie (NO_TICK si jamais vu). */
    public long[] lastT;
    /** Spread median par symbole (MED du POC). */
    public double[] med;
    public boolean[] isFx;
    /** edge FX (0..28) -> indice serie chargee (E_S du POC), -1 si absent. */
    public final int[] fxSi = new int[Universe.FX.length];
    /** boutSi[b] = indice de serie du bout b (-1 si absent des donnees). */
    public final int[] boutSi = new int[BoutUniverse.BOUTS.length];
    /** Paire USD par devise : {serie, direct?1:0}, null si absente. */
    private final int[][] upPair = new int[8][];
    /** Accumulateurs de spread pour la mediane incrementale. */
    private SpreadMedian[] medAcc;
    /** Evenements FX tries (ts, si) pour le scan evenementiel. */
    public final List<long[]> ev = new ArrayList<>();
    /**
     * Commission par cote et par position ~10K (defaut commUsd() = 0,40).
     * Un seul parametre lu par eval, execution ET liquidation.
     */
    public double commPerSide = Fees.commUsd();

    /**
     * Cutoff mediane de spread (ms) : null = tout l'echantillon
     * (comportement live). Non nul = n'accumule que les ticks <= cutoff
     * — requis en replay multi-jours : sinon la mediane absorbe le
     * holdout (fuite d'information, MED_CUTOFF du POC).
     */
    public Long medUntilMs = null;

    /** Buffers eval reutilises (zero-alloc dans la boucle chaude). */
    private boolean[] okW;
    private boolean[] fxOkW;
    private final double[] pre3 = new double[3];
    /** spreadCost(si) calcule une fois par eval. */
    private double[] spcW;
    /** Cache prefixe FX par chemin pour evalBoutAt : 0 inconnu, 1 ok, 2 ko. */
    private byte[] pxState;
    private double[] pxRate;
    private double[] pxComm;
    private double[] pxUnw;

    /** Construit l'etat a partir de series chargees (deja triees par ts). */
    public Engine(List<SymbolSeries> series) {
        build(new ArrayList<>(series), true);
    }

    private Engine() {}

    /** Moteur "live" : 28 FX + 26 bouts, slots vides sans historique. */
    public static Engine live() {
        List<SymbolSeries> series = new ArrayList<>();
        for (String sym : Universe.FX) {
            series.add(new SymbolSeries(sym));
        }
        for (String sym : BoutUniverse.symbols()) {
            series.add(new SymbolSeries(sym));
        }
        Engine e = new Engine();
        e.build(series, false);
        return e;
    }

    private void build(List<SymbolSeries> series, boolean dropEmpty) {
        if (dropEmpty) {
            series.removeIf(SymbolSeries::isEmpty);
        }
        series.sort(Comparator.comparing(s -> s.sym));
        int n = series.size();
        bid = new double[n];
        ask = new double[n];
        lastT = new long[n];
        med = new double[n];
        isFx = new boolean[n];
        medAcc = new SpreadMedian[n];
        okW = new boolean[n];
        fxOkW = new boolean[n];
        spcW = new double[n];
        Arrays.fill(bid, Double.NaN);
        Arrays.fill(ask, Double.NaN);
        Arrays.fill(lastT, NO_TICK);
        Arrays.fill(fxSi, -1);
        Arrays.fill(boutSi, -1);
        List<String> fx = Arrays.asList(Universe.FX);
        pos = new long[n];
        for (int i = 0; i < n; i++) {
            SymbolSeries s = series.get(i);
            sidx.put(s.sym, i);
            syms.add(s.sym);
            SpreadMedian acc = new SpreadMedian();
            for (int k = 0; k < s.len(); k++) {
                acc.push(s.ask[k] - s.bid[k]);
            }
            med[i] = acc.median();
            medAcc[i] = acc;
            isFx[i] = fx.contains(s.sym);
        }
        for (int b = 0; b < BoutUniverse.BOUTS.length; b++) {
            Integer si = sidx.get(BoutUniverse.BOUTS[b].name);
            if (si != null) {
                boutSi[b] = si;
            }
        }
        for (int e = 0; e < Universe.FX.length; e++) {
            Integer si = sidx.get(Universe.FX[e]);
            if (si != null) {
                fxSi[e] = si;
            }
        }
        // paire USD de reference par devise : "cUSD" (direct) ou "USDc"
        for (int i = 0; i < Universe.CURR.length; i++) {
            String c = Universe.CURR[i];
            if (c.equals("USD")) {
                continue;
            }
            Integer si = sidx.get(c + "USD");
            if (si != null) {
                upPair[i] = new int[] {si, 1};
            } else {
                si = sidx.get("USD" + c);
                if (si != null) {
                    upPair[i] = new int[] {si, 0};
                }
            }
        }
        for (int si : fxSi) {
            if (si < 0) {
                continue;
            }
            SymbolSeries s = series.get(si);
            for (int k = 0; k < s.len(); k++) {
                ev.add(new long[] {s.ts[k], si});
            }
        }
        // tri stable par ts — meme tie-break que np.argsort
        ev.sort(Comparator.comparingLong(a -> a[0]));
        data.addAll(series);
    }

    /** advance : avance/rembobine le curseur vers le dernier tick <= t.
     * Retourne l'indice du tick, -1 si aucun tick <= t. */
    private int advance(int si, long t) {
        SymbolSeries s = data.get(si);
        long j = pos[si];
        if (j < 0 || j >= s.len() || s.ts[(int) j] > t) {
            j = s.partitionPoint(t);
        } else {
            while (j + 1 < s.len() && s.ts[(int) j + 1] <= t) {
                j++;
            }
        }
        pos[si] = j;
        return (int) j;
    }

    /** Met a jour l'etat avec le tick de si a t — jamais de quote future. */
    public void tick(int si, long t) {
        int j = advance(si, t);
        publish(si, j, t);
    }

    /**
     * Ingestion live : ajoute le tick a la serie, met a jour la mediane
     * incrementale du spread et publie l'etat — jamais de quote future.
     * Preconditions : flux par instrument non decroissant en tsMs.
     * Un tsMs egal au dernier remplace la quote ; le spread n'entre dans
     * la mediane qu'a chaque NOUVELLE estampille (dedup re-emission).
     * Quotes rejetees : bid/ask non finis ou croises (ask < bid).
     */
    public void ingest(int si, Quote q) {
        if (!Double.isFinite(q.bid) || !Double.isFinite(q.ask) || q.ask < q.bid) {
            return;
        }
        SymbolSeries s = data.get(si);
        boolean appended;
        if (s.len() > 0 && s.ts[s.len() - 1] == q.tsMs) {
            s.replaceLast(q.bid, q.ask, q.vol, q.vol);
            appended = false;
        } else {
            if (s.len() > 0 && q.tsMs <= s.ts[s.len() - 1]) {
                return; // tick hors ordre — casserait partitionPoint
            }
            s.push(q.tsMs, q.bid, q.ask, q.vol, q.vol);
            appended = true;
        }
        if (appended && (medUntilMs == null || q.tsMs <= medUntilMs)) {
            medAcc[si].push(q.ask - q.bid);
            med[si] = medAcc[si].median();
        }
        int j = s.len() - 1;
        pos[si] = j;
        publish(si, j, q.tsMs);
    }

    /**
     * Fixe le cutoff mediane in-sample et le recalcule sur les series
     * chargees — le replay multi-jours appelle ceci avec l'epoch du
     * dernier jour in-sample (equivalent MED_CUTOFF du POC ; supprime
     * la fuite du holdout dans le cap spread_cap x med).
     */
    public void setMedUntil(long ms) {
        medUntilMs = ms;
        for (int si = 0; si < data.size(); si++) {
            SymbolSeries s = data.get(si);
            SpreadMedian acc = new SpreadMedian();
            for (int k = 0; k < s.len(); k++) {
                if (s.ts[k] <= ms) {
                    acc.push(s.ask[k] - s.bid[k]);
                }
            }
            medAcc[si] = acc;
            med[si] = acc.median();
        }
    }

    /** Publie l'etat a l'instant t pour tous les symboles (forward-fill). */
    public void sync(long t) {
        for (int si = 0; si < data.size(); si++) {
            int j = advance(si, t);
            publish(si, j, t);
        }
    }

    private void publish(int si, int j, long t) {
        SymbolSeries s = data.get(si);
        if (j >= 0 && s.ts[j] <= t) {
            bid[si] = s.bid[j];
            ask[si] = s.ask[j];
            lastT[si] = s.ts[j];
        } else {
            bid[si] = Double.NaN;
            ask[si] = Double.NaN;
            lastT[si] = NO_TICK;
        }
    }

    /**
     * Dernier tick <= t — sans effet de bord.
     * Retourne {ts, bid, ask, vol=max(avol,bvol)} ou null.
     */
    public double[] quoteAt(int si, long t) {
        double[] q = quoteAtF(si, t);
        if (q == null) {
            return null;
        }
        return new double[] {q[0], q[1], q[2], Math.max(q[3], q[4])};
    }

    /**
     * quoteAt a t flottant (tFill non entier).
     * Retourne {ts, bid, ask, avol, bvol} ou null.
     */
    public double[] quoteAtF(int si, double t) {
        SymbolSeries s = data.get(si);
        int j = s.partitionPointF(t);
        if (j < 0) {
            return null;
        }
        return new double[] {s.ts[j], s.bid[j], s.ask[j], s.avol[j], s.bvol[j]};
    }

    /** Quote valide : publiee ET pas future ET fraiche (bilaterale R7). */
    public boolean ok(int si, long t, double freshMs) {
        return lastT[si] != NO_TICK
                && lastT[si] <= t
                && (t - lastT[si]) <= freshMs;
    }

    /** Prix USD courant de chaque noeud (mid) ; NaN si paire absente. */
    public double[] usdPrice(boolean[] ok) {
        double[] up = new double[8];
        Arrays.fill(up, Double.NaN);
        up[0] = 1.0;
        for (int i = 0; i < 8; i++) {
            int[] p = upPair[i];
            if (p == null || !ok[p[0]]) {
                continue;
            }
            double m = 0.5 * (bid[p[0]] + ask[p[0]]);
            up[i] = p[1] == 1 ? m : 1.0 / m;
        }
        return up;
    }

    /**
     * Prix USD EXECUTABLE de liquidation par noeud (Convention A stricte) :
     * cUSD -> bid ; USDc -> 1/ask. NaN si paire absente ou non ok.
     */
    public double[] usdLiqPrice(boolean[] ok) {
        double[] up = new double[8];
        Arrays.fill(up, Double.NaN);
        up[0] = 1.0;
        for (int i = 0; i < 8; i++) {
            int[] p = upPair[i];
            if (p == null || !ok[p[0]]) {
                continue;
            }
            up[i] = p[1] == 1 ? bid[p[0]] : 1.0 / ask[p[0]];
        }
        return up;
    }

    /**
     * Commission de la jambe de liquidation noeud -> USD (modele COMM/100k
     * de la devise de base de la paire USD). cUSD -> COMM/mid ; USDc -> COMM.
     */
    public double[] usdLiqComm(boolean[] ok) {
        double[] c = new double[8];
        Arrays.fill(c, Double.NaN);
        c[0] = 0.0;
        for (int i = 0; i < 8; i++) {
            int[] p = upPair[i];
            if (p == null || !ok[p[0]]) {
                continue;
            }
            c[i] = p[1] == 1
                    ? commPerSide / (0.5 * (bid[p[0]] + ask[p[0]]))
                    : commPerSide;
        }
        return c;
    }

    /** Serie + direction de la paire USD : {si, direct?1:0} ou null. */
    int[] usdPair(int c) {
        return upPair[c];
    }

    /** Indices de devises d'une arete : {base, quote}. */
    public static int[] edgeNodes(int edge) {
        return Universe.pairNodes(edge);
    }

    /** Noeud "autre cote" de l'arete depuis node. */
    public static int edgeOther(int edge, int node) {
        int[] ab = Universe.pairNodes(edge);
        return node == ab[0] ? ab[1] : ab[0];
    }

    // ======================== eval.rs ========================

    /**
     * Evalue les 2 072 chemins sur l'etat courant ; retourne le meilleur
     * net executable, ou null si aucun chemin valide. Premier maximum en
     * cas d'egalite (np.argmax).
     */
    public EvalResult evalAt(long t, double freshMs, double spreadCap,
                             double commScale, PathTables pt) {
        int n = data.size();
        boolean[] ok = okW;
        for (int i = 0; i < n; i++) {
            ok[i] = ok(i, t, freshMs);
        }
        boolean[] fxOk = fxOkW;
        for (int i = 0; i < n; i++) {
            fxOk[i] = ok[i]
                    && !(isFx[i] && (ask[i] - bid[i]) > spreadCap * med[i]);
            spcW[i] = spreadCost(i);
        }

        double bestNet = Double.NEGATIVE_INFINITY;
        int bestPi = 0;
        double bestDdd = 0.0;

        for (int pi = 0; pi < pt.len(); pi++) {
            int end = pt.pEnd[pi];
            if (!prefix(pi, end, fxOk, pt)) {
                continue;
            }
            double rate = pre3[0];
            double comm = pre3[1];
            double unwind = pre3[2];
            // sortie end->USD : position reelle
            if (end != 0) {
                int[] xe = usdPair(end);
                if (xe == null || !fxOk[xe[0]]) {
                    continue;
                }
                rate *= xe[1] == 1 ? bid[xe[0]] : 1.0 / ask[xe[0]];
                unwind += spcW[xe[0]];
                comm += commPerSide;
            }
            double usdOut = Fees.NOTIONAL_USD * rate;
            double commFx = 2.0 * comm * commScale;
            if (!(Double.isFinite(usdOut) && Double.isFinite(commFx)
                    && Double.isFinite(unwind))) {
                continue;
            }
            double net = usdOut - Fees.NOTIONAL_USD - commFx - unwind;
            if (net > bestNet) {
                bestNet = net;
                bestPi = pi;
                bestDdd = usdOut - Fees.NOTIONAL_USD;
            }
        }

        if (!Double.isFinite(bestNet)) {
            return null;
        }
        return new EvalResult(bestNet, bestPi, bestDdd);
    }

    /**
     * Prefixe executable d'un chemin : entree USD->s0 + jambes FX, SANS la
     * sortie terminale. Retourne {rate, comm, unwind} ou null si une quote
     * manque/degarde. Factorise evalAt et evalBoutAt (meme ordre f64).
     */
    private boolean prefix(int pi, int end, boolean[] fxOk, PathTables pt) {
        int s0 = pt.pS[pi];
        double rate = 1.0;
        double comm = 0.0;
        double unwind = 0.0;
        if (s0 != 0) {
            int[] se = usdPair(s0);
            if (se == null || !fxOk[se[0]]) {
                return false;
            }
            rate *= se[1] == 1 ? 1.0 / ask[se[0]] : bid[se[0]];
            unwind += spcW[se[0]];
            comm += commPerSide;
        }
        for (int li = 0; li < pt.pL[pi]; li++) {
            int e = pt.pE[pi][li];
            int si = fxSi[e];
            if (si < 0 || !fxOk[si]) {
                return false;
            }
            rate *= pt.pDir[pi][li] == 1 ? bid[si] : 1.0 / ask[si];
            unwind += spcW[si];
            comm += commPerSide;
        }
        pre3[0] = rate;
        pre3[1] = comm;
        pre3[2] = unwind;
        return true;
    }

    /**
     * Eval des chemins etendus (prefixe FX + bout terminal).
     * Convention A : le bout est achete a l'ask, revendu au bid, produit
     * en devise cotee c puis liquide c->USD a prix executable. Retourne le
     * meilleur {net, index dans xt, ddd}.
     */
    public EvalResult evalBoutAt(long t, double freshMs, double spreadCap,
                                 double commScale, PathTables pt,
                                 ExtPathTables xt) {
        int n = data.size();
        boolean[] ok = okW;
        boolean[] fxOk = fxOkW;
        for (int i = 0; i < n; i++) {
            ok[i] = ok(i, t, freshMs);
            fxOk[i] = ok[i]
                    && !(isFx[i] && (ask[i] - bid[i]) > spreadCap * med[i]);
            spcW[i] = spreadCost(i);
        }
        if (pxState == null || pxState.length != pt.len()) {
            pxState = new byte[pt.len()];
            pxRate = new double[pt.len()];
            pxComm = new double[pt.len()];
            pxUnw = new double[pt.len()];
        } else {
            java.util.Arrays.fill(pxState, (byte) 0);
        }

        double bestNet = Double.NEGATIVE_INFINITY;
        int bestXi = 0;
        double bestDdd = 0.0;

        for (int xi = 0; xi < xt.len(); xi++) {
            int b = xt.bout[xi];
            int bsi = boutSi[b];
            if (bsi < 0 || !ok[bsi]) {
                continue;
            }
            Bout bt = BoutUniverse.BOUTS[b];
            int end = xt.end[xi];
            int fpi = xt.fxPi[xi];
            if (pxState[fpi] == 0) {
                if (prefix(fpi, end, fxOk, pt)) {
                    pxState[fpi] = 1;
                    pxRate[fpi] = pre3[0];
                    pxComm[fpi] = pre3[1];
                    pxUnw[fpi] = pre3[2];
                } else {
                    pxState[fpi] = 2;
                }
            }
            if (pxState[fpi] == 2) {
                continue;
            }
            double rate = pxRate[fpi];
            double comm = pxComm[fpi];
            double unwind = pxUnw[fpi];

            // arete bout : achat ask / revente bid dans la devise cotee
            double aB = ask[bsi];
            double bB = bid[bsi];
            if (!(aB > 0.0 && bB > 0.0)) {
                continue;
            }
            double qtyC = Fees.NOTIONAL_USD * rate;      // en devise c
            double units = qtyC / aB;
            double proceedsC = units * bB;
            unwind += spcW[bsi];                         // re-traversee bout
            // liquidation c -> USD (executable)
            if (end != 0) {
                int[] xe = usdPair(end);
                if (xe == null || !fxOk[xe[0]]) {
                    continue;
                }
                proceedsC *= xe[1] == 1 ? bid[xe[0]] : 1.0 / ask[xe[0]];
                unwind += spcW[xe[0]];
                comm += commPerSide;
            }
            double commFx = 2.0 * comm * commScale;
            double commBout = 2.0 * bt.commUsdPerLotSide
                    * (units / bt.contractSize) * commScale;
            if (!(Double.isFinite(proceedsC) && Double.isFinite(commFx)
                    && Double.isFinite(commBout)
                    && Double.isFinite(unwind))) {
                continue;
            }
            double net = proceedsC - Fees.NOTIONAL_USD
                    - commFx - commBout - unwind;
            if (net > bestNet) {
                bestNet = net;
                bestXi = xi;
                bestDdd = proceedsC - Fees.NOTIONAL_USD;
            }
        }
        if (!Double.isFinite(bestNet)) {
            return null;
        }
        return new EvalResult(bestNet, bestXi, bestDdd);
    }

    /** Cout de re-traversee du spread a la cloture d'une position ~10K. */
    private double spreadCost(int si) {
        double mid = 0.5 * (bid[si] + ask[si]);
        if (Double.isFinite(mid) && mid > 0.0) {
            return (ask[si] - bid[si]) / mid * Fees.NOTIONAL_USD;
        }
        return Double.NaN;
    }

    // ======================== exec.rs ========================

    /**
     * Rejoue la sequence IOC du chemin pi detecte a t0.
     * Retourne null si une donnee requise est absente (POC : skip).
     * Meme ordre d'operations f64 que le Rust — parite 1e-12 requise.
     */
    public ExecOutcome execSeq(long t0, int pi, ExecParams ep, PathTables pt) {
        int[][] orders = ordersOf(pi, pt);
        if (orders == null) {
            return null;
        }
        sync(t0);
        // usd_price/usd_liq sur TOUS les mids courants (ok = tous vrais) —
        // le marquage du reliquat de partiel ne subit pas la garde fraicheur.
        boolean[] okAll = new boolean[data.size()];
        Arrays.fill(okAll, true);
        double[] up = usdPrice(okAll);
        double[] upLiq = usdLiqPrice(okAll);
        double[] commLiq = usdLiqComm(okAll);

        int[] orderEdges = new int[orders.length];
        for (int i = 0; i < orders.length; i++) {
            orderEdges[i] = orders[i][0];
        }
        double qty = Fees.NOTIONAL_USD; // on demarre en USD
        double cash = 0.0;
        double comm = 0.0;
        double t = t0;
        int filled = 0;
        int parts = 0;
        double slip = ep.tauSlipBp * 1e-4;
        double unwindSoFar = 0.0;

        for (int oi = 0; oi < orders.length; oi++) {
            int[] ord = orders[oi];
            int e = ord[0];
            int from = ord[1];
            int si = fxSi[e];
            int[] ab = edgeNodes(e);
            int d = from == ab[0] ? 1 : -1;
            int node = from; // devise convertie par cet ordre
            double tF = t + ep.latMs + jit(t0, oi, ep.jitterMs);

            double[] qs = quoteAtF(si, t);
            double qSend = qs != null
                    ? (d == 1 ? qs[1] : 1.0 / qs[2])
                    : Double.NaN;
            double[] qf = quoteAtF(si, tF);
            if (qf == null) {
                return fail(AbortReason.NO_QUOTE, node, qty, t, ep,
                        orderEdges, filled, parts, cash, comm, t0);
            }
            if (!Double.isFinite(qSend) || qSend <= 0.0) {
                return fail(AbortReason.NO_QUOTE, node, qty, t, ep,
                        orderEdges, filled, parts, cash, comm, t0);
            }
            if (ep.docMode) {
                // E3a : fraicheur de la quote au fill
                if (tF - qf[0] > ep.staleMs) {
                    return fail(AbortReason.STALE_QUOTE, node, qty, t, ep,
                            orderEdges, filled, parts, cash, comm, t0);
                }
                // E3b : spread <= 1.7 x mediane
                if (qf[2] - qf[1] > ep.spreadCapMult * med[si]) {
                    return fail(AbortReason.SPREAD_WIDE, node, qty, t, ep,
                            orderEdges, filled, parts, cash, comm, t0);
                }
            }

            double qNow = d == 1 ? qf[1] : 1.0 / qf[2];
            double mv = Math.abs(qNow / qSend - 1.0);
            if (mv > 3.0 * slip) {
                return fail(AbortReason.KILL_IOC, node, qty, t, ep,
                        orderEdges, filled, parts, cash, comm, t0);
            }
            double frac = mv <= slip ? 1.0 : 0.6;

            // garde volume top-of-book — volume du cote atteint
            if (ep.volGuard) {
                double volBase = (d == 1 ? qf[4] : qf[3]) * 1e6;
                double needBase = d == 1 ? qty : qty * qNow;
                double fracVol = Double.isFinite(volBase) && volBase > 0.0
                        && needBase > 0.0
                        ? Math.min(volBase / needBase, 1.0)
                        : 0.0;
                frac = Math.min(frac, fracVol);
            }

            if (frac < ep.qMin) {
                return fail(AbortReason.BELOW_QMIN, node, qty, t, ep,
                        orderEdges, filled, parts, cash, comm, t0);
            }
            if (frac < 1.0) {
                double[] rc = liq(node, up, upLiq, commLiq);
                cash += qty * (1.0 - frac) * rc[0];
                comm += rc[1];
                qty *= frac;
                parts += 1;
            }
            qty *= qNow;
            comm += commPerSide; // flat par position ~10K (parite eval)
            unwindSoFar += spreadCost(si);
            filled += 1;
            t = tF;

            if (ep.docMode && oi + 1 < orders.length) {
                // E4 : E[net residuel] du reste du chemin aux marks
                // courants ; continue ssi >= -(C_unwind + m_abort)
                double rem = remainingRate(orders, oi + 1, t, pt);
                double[] rc = liqAt(node, t);
                double cUnwind = unwindSoFar;
                double eResid = rem > 0.0 && rc != null
                        ? qty * rem - qty * rc[0]
                        : Double.NEGATIVE_INFINITY;
                if (eResid < -(cUnwind + ep.mAbortUsd)) {
                    return fail(AbortReason.E4_RESIDUAL, node, qty, t, ep,
                            orderEdges, filled, parts, cash, comm, t0);
                }
            }
        }

        // node == 0 : le panier termine en USD — usdEnd = qty directement.
        double usdEnd = qty;
        double unwind = 0.0;
        double commRt = comm;
        for (int i = 0; i < filled; i++) {
            int si = fxSi[orderEdges[i]];
            if (si < 0) {
                continue;
            }
            unwind += spreadCost(si);
            commRt += commPerSide;
        }
        return new ExecOutcome.Done(
                usdEnd + cash - Fees.NOTIONAL_USD - commRt - unwind,
                filled, parts, t - t0);
    }

    /** Jitter de latence deterministe +-jitterMs en (t0, oi) — reproductible
     *  a l'identique entre runs (parite) sans etat RNG partage. */
    private static double jit(long t0, int oi, double jitterMs) {
        if (jitterMs <= 0.0) {
            return 0.0;
        }
        long h = t0 * 2654435761L + oi * 40503L;
        double u = (h & 0xFFFF) / 65535.0;      // [0,1] deterministe
        return jitterMs * (2.0 * u - 1.0);      // [-jitter, +jitter]
    }

    private ExecOutcome abort(AbortReason reason, double aCost, double cash,
                              double comm, int filled, int parts,
                              double durMs) {
        return new ExecOutcome.Abort(reason,
                -aCost + cash - comm, filled, parts, durMs);
    }

    /**
     * Repartiteur d'echec : docMode -> unwind reel E6/E7 ; sinon
     * l'ancien modele abortCost (parite Rust preservee).
     */
    private ExecOutcome fail(AbortReason reason, int node, double qty,
                             double t, ExecParams ep, int[] orderEdges,
                             int filled, int parts, double cash,
                             double comm, double t0) {
        if (!ep.docMode) {
            return abort(reason, abortCost(orderEdges, filled), cash,
                    comm, filled, parts, t - t0);
        }
        // E6 : unwind reel — liquide la position node->USD au marche,
        // retry N_u fois a t + k*lat
        for (int k = 1; k <= Math.max(1, ep.nU); k++) {
            double[] rc = liqAt(node, t + k * ep.latMs);
            if (rc != null) {
                cash += qty * rc[0];
                comm += rc[1];
                return new ExecOutcome.Abort(reason,
                        cash - comm - Fees.NOTIONAL_USD, filled, parts,
                        t + k * ep.latMs - t0, 0.0);
            }
        }
        // E7 : residuel non liquide — reste ouvert, marque executable
        double[] mark = liqAt(node, t);
        double resid = mark != null ? qty * mark[0] : qty;
        return new ExecOutcome.Abort(reason,
                cash - comm - Fees.NOTIONAL_USD, filled, parts, t - t0,
                resid);
    }

    /** Liquidation executable node->USD a l'instant tA, ou null. */
    private double[] liqAt(int node, double tA) {
        if (node == 0) {
            return new double[] {1.0, 0.0};
        }
        int[] se = usdPair(node);
        if (se == null) {
            return null;
        }
        int si = fxSi[se[0]];
        if (si < 0) {
            return null;
        }
        double[] q = quoteAtF(si, tA);
        if (q == null || !(q[1] > 0.0 && q[2] > 0.0)) {
            return null;
        }
        double rate = se[1] == 1 ? q[1] : 1.0 / q[2];
        return new double[] {rate, commPerSide};
    }

    /**
     * Produit des taux executables des ordres [from..L) aux marks de t
     * (chute de quote -> -1).
     */
    private double remainingRate(int[][] orders, int from, double t,
                                 PathTables pt) {
        double rem = 1.0;
        for (int k = from; k < orders.length; k++) {
            int e = orders[k][0];
            int si = fxSi[e];
            if (si < 0) {
                return -1.0;
            }
            int[] ab = edgeNodes(e);
            int d = orders[k][1] == ab[0] ? 1 : -1;
            double[] q = quoteAtF(si, t);
            if (q == null) {
                return -1.0;
            }
            double r = d == 1 ? q[1] : 1.0 / q[2];
            if (!(r > 0.0) || !Double.isFinite(r)) {
                return -1.0;
            }
            rem *= r;
        }
        return rem;
    }

    /**
     * Ordres du panier marge du chemin pi : {arete, noeud_depart} —
     * [USD->s0] + jambes + [end->USD]. null si une arete n'est pas chargee.
     */
    public int[][] ordersOf(int pi, PathTables pt) {
        int start = pt.pS[pi];
        int end = pt.pEnd[pi];
        List<int[]> orders = new ArrayList<>(pt.pL[pi] + 2);
        if (start != 0) {
            int e = Universe.usdEdge(start);
            if (e < 0 || fxSi[e] < 0) {
                return null;
            }
            orders.add(new int[] {e, 0});
        }
        int node = start;
        for (int li = 0; li < pt.pL[pi]; li++) {
            int e = pt.pE[pi][li];
            if (fxSi[e] < 0) {
                return null;
            }
            orders.add(new int[] {e, node});
            node = edgeOther(e, node);
        }
        if (end != 0) {
            int e = Universe.usdEdge(end);
            if (e < 0 || fxSi[e] < 0) {
                return null;
            }
            orders.add(new int[] {e, end});
        }
        return orders.toArray(new int[0][]);
    }

    /** Montant de depart du panier — toujours NOTIONAL_USD en USD. */
    public Double startQty(int pi, PathTables pt) {
        if (ordersOf(pi, pt) == null) {
            return null;
        }
        return Fees.NOTIONAL_USD;
    }

    /** Cout de debouclage des positions remplies (OG2-D2) : med_rel x
     * notional + comm par ordre rempli ; repli 12 $ si mediane absente. */
    private double abortCost(int[] legs, int filled) {
        double sum = 0.0;
        for (int i = 0; i < filled; i++) {
            int si = fxSi[legs[i]];
            if (si < 0) {
                sum += Fees.ABORT_COST_PER_LEG_USD;
                continue;
            }
            double mid = (bid[si] + ask[si]) * 0.5;
            double m = med[si];
            if (Double.isFinite(m) && Double.isFinite(mid) && mid > 0.0) {
                sum += m / mid * Fees.NOTIONAL_USD + commPerSide;
            } else {
                sum += Fees.ABORT_COST_PER_LEG_USD;
            }
        }
        return sum;
    }

    /** Liquidation executable d'un noeud : {rate, comm} Convention A. */
    private double[] liq(int node, double[] up, double[] upLiq,
                         double[] commLiq) {
        double r = Double.isFinite(upLiq[node]) ? upLiq[node] : up[node];
        double c;
        if (Double.isFinite(commLiq[node])) {
            c = commLiq[node];
        } else if (Double.isFinite(up[node]) && up[node] > 0.0) {
            c = commPerSide / up[node];
        } else {
            c = commPerSide;
        }
        return new double[] {r, c};
    }

    /**
     * Boucle une sequence executee par une venue externe — meme arithmetique
     * de bouclage que execSeq appliquee aux fills constates.
     * fills = {fracEff, pxNode} par ordre du panier complete ; aborted :
     * sequence arretee avant le dernier ordre.
     * Retourne {pnlUsd, filledLegs, partialLegs, residUsd} ou null si une
     * arete du panier n'est pas chargee ou fills > ordres.
     */
    public double[] execSettle(int pi, double[][] fills, boolean aborted,
                               PathTables pt) {
        int[][] orders = ordersOf(pi, pt);
        if (orders == null || fills.length > orders.length) {
            return null;
        }
        boolean[] okAll = new boolean[data.size()];
        Arrays.fill(okAll, true);
        double[] up = usdPrice(okAll);
        double[] upLiq = usdLiqPrice(okAll);
        double[] commLiq = usdLiqComm(okAll);

        int[] orderEdges = new int[orders.length];
        for (int i = 0; i < orders.length; i++) {
            orderEdges[i] = orders[i][0];
        }
        int node = 0; // USD — panier : depart en USD explicite
        double qty = Fees.NOTIONAL_USD;
        double cash = 0.0;
        double comm = 0.0;
        int filled = 0;
        int parts = 0;

        for (int li = 0; li < fills.length; li++) {
            double frac = fills[li][0];
            double px = fills[li][1];
            int from = orders[li][1];
            node = from;
            if (frac < 1.0) {
                double[] rc = liq(node, up, upLiq, commLiq);
                cash += qty * (1.0 - frac) * rc[0];
                comm += rc[1];
                qty *= frac;
                parts += 1;
            }
            qty *= px;
            comm += commPerSide; // flat par position (parite eval)
            filled += 1;
            node = edgeOther(orderEdges[li], from);
        }

        double[] rcEnd = liq(node, up, upLiq, commLiq);
        double residUsd = filled > 0 ? qty * rcEnd[0] : 0.0;
        double pnl;
        if (aborted) {
            pnl = -abortCost(orderEdges, filled) + cash - comm;
        } else {
            double unwind = 0.0;
            for (int i = 0; i < filled; i++) {
                int si = fxSi[orderEdges[i]];
                if (si < 0) {
                    continue;
                }
                unwind += spreadCost(si);
                comm += commPerSide;
            }
            pnl = residUsd + cash - Fees.NOTIONAL_USD - comm - unwind;
        }
        return new double[] {pnl, filled, parts, residUsd};
    }

    // ======================== scan.rs ========================

    /** Parametres calibrables — le jeu complet du POC Optuna. */
    public static class Params {
        public double freshMs, spreadCap, commScale, dddMin, latMs;
        public double tauSlipBp, qMin, cooldownS;
        public boolean volGuard;
        /** EXT (opt-in, hors miroir Optuna scelle) : delai d'entree
         * applique a l'execution — la detection est pricee a t mais la
         * sequence part a t+entryDelayMs. Defaut 0 = semantique scellee. */
        public double entryDelayMs;
        /** EXT : le signal (meme argmax net>=dddMin) doit persister au
         * moins persistMinMs avant d'executer. Defaut 0 = inactif. */
        public double persistMinMs;

        public Params() {}

        public Params(double freshMs, double spreadCap, double commScale,
                      double dddMin, double latMs, double tauSlipBp,
                      double qMin, double cooldownS, boolean volGuard) {
            this.freshMs = freshMs;
            this.spreadCap = spreadCap;
            this.commScale = commScale;
            this.dddMin = dddMin;
            this.latMs = latMs;
            this.tauSlipBp = tauSlipBp;
            this.qMin = qMin;
            this.cooldownS = cooldownS;
            this.volGuard = volGuard;
        }

        /** Defauts du document (DOC_DEFAULTS du POC — baseline). */
        public static Params docDefaults() {
            return new Params(500.0, 1.7, 1.0, 0.0, 2.5, 0.8, 0.5, 0.0, false);
        }
    }

    /** Candidat de detection : {tMs, pathIdx, net, ddd}. */
    public static class Cand {
        public final long tMs;
        public final int pathIdx;
        public final double net;
        public final double ddd;

        public Cand(long tMs, int pathIdx, double net, double ddd) {
            this.tMs = tMs;
            this.pathIdx = pathIdx;
            this.net = net;
            this.ddd = ddd;
        }
    }

    /** Episode d'opportunite : fenetre contigue net > 0. */
    public static class Episode {
        public final long t0;
        public long t1;
        public final int pathIdx;
        public double netMax;

        public Episode(long t0, int pathIdx, double netMax) {
            this.t0 = t0;
            this.t1 = t0;
            this.pathIdx = pathIdx;
            this.netMax = netMax;
        }
    }

    public static class ScanStats {
        public long nEvents;
        public long nEvals;
    }

    /** Resultat de scan : candidats + episodes + stats. */
    public static class ScanOut {
        public final List<Cand> cands;
        public final List<Episode> episodes;
        public final ScanStats stats;

        ScanOut(List<Cand> c, List<Episode> e, ScanStats s) {
            cands = c;
            episodes = e;
            stats = s;
        }
    }

    /** Passe unique sur les evenements FX. */
    public ScanOut scan(PathTables pt, double freshMs, double spreadCap,
                        double thresh, double moveGate, long evalEveryMs) {
        List<Cand> cands = new ArrayList<>();
        List<Episode> episodes = new ArrayList<>();
        Episode openEp = null;
        long lastEvalT = Long.MIN_VALUE;
        double[] evalMid = new double[data.size()];
        Arrays.fill(evalMid, Double.NaN);
        ScanStats stats = new ScanStats();

        for (long[] e2 : ev) {
            long t = e2[0];
            int i = (int) e2[1];
            tick(i, t);
            stats.nEvents++;
            double mid = 0.5 * (bid[i] + ask[i]);
            boolean moved = !Double.isFinite(evalMid[i])
                    || Math.abs(mid - evalMid[i]) > moveGate * med[i];
            if (!moved && t - lastEvalT < evalEveryMs) {
                continue;
            }
            lastEvalT = t;
            for (int j = 0; j < data.size(); j++) {
                evalMid[j] = 0.5 * (bid[j] + ask[j]);
            }
            stats.nEvals++;
            EvalResult er = evalAt(t, freshMs, spreadCap, 1.0, pt);
            if (er == null) {
                continue; // eval None -> continue SANS fermer l'episode
            }
            if (er.netUsd > thresh) {
                cands.add(new Cand(t, er.pathIdx, er.netUsd, er.dddUsd));
            }
            if (er.netUsd > 0.0) {
                if (openEp == null) {
                    openEp = new Episode(t, er.pathIdx, er.netUsd);
                } else {
                    openEp.netMax = Math.max(openEp.netMax, er.netUsd);
                }
            } else if (openEp != null) {
                openEp.t1 = t;
                episodes.add(openEp);
                openEp = null;
            }
        }
        if (openEp != null) {
            openEp.t1 = ev.isEmpty() ? 0 : ev.get(ev.size() - 1)[0];
            episodes.add(openEp);
        }
        return new ScanOut(cands, episodes, stats);
    }

    /** Agregat d'un replay — miroir du dict retourne par le POC. */
    public static class ReplayResult {
        public final double pnl;
        public final long trades;
        public final long aborts;
        public final double winRate;
        public final double durMsMean;

        ReplayResult(double pnl, long trades, long aborts, double winRate,
                     double durMsMean) {
            this.pnl = pnl;
            this.trades = trades;
            this.aborts = aborts;
            this.winRate = winRate;
            this.durMsMean = durMsMean;
        }
    }

    /** Rejoue les candidats sous les parametres du trial (poc_optuna.replay). */
    public ReplayResult replay(List<Cand> cands, Params p, PathTables pt) {
        double pnl = 0.0;
        long n = 0, aborts = 0, wins = 0;
        double durSum = 0.0;
        double tFree = Double.NEGATIVE_INFINITY;
        ExecParams ep = new ExecParams(p.latMs, p.tauSlipBp, p.qMin,
                p.volGuard);
        for (Cand c : cands) {
            if (c.tMs < tFree) {
                continue;
            }
            sync(c.tMs);
            EvalResult evr = evalAt(c.tMs, p.freshMs, p.spreadCap,
                    p.commScale, pt);
            if (evr == null || evr.netUsd < p.dddMin) {
                continue;
            }
            ExecOutcome r = execSeq(c.tMs, evr.pathIdx, ep, pt);
            if (r == null) {
                continue;
            }
            if (r instanceof ExecOutcome.Abort) {
                aborts++;
            }
            if (r.pnl() > 0.0) {
                wins++;
            }
            pnl += r.pnl();
            n++;
            durSum += r.durMs();
            tFree = c.tMs + Math.max(r.durMs(), 1.0) + p.cooldownS * 1000.0;
        }
        return new ReplayResult(pnl, n, aborts, n > 0 ? (double) wins / n : 0.0,
                n > 0 ? durSum / n : 0.0);
    }
}
