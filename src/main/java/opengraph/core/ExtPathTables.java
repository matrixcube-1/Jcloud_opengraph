package opengraph.core;

import java.util.ArrayList;
import java.util.List;

/**
 * Chemins etendus = prefixe FX (1-3 jambes, noeud terminal c) + arete
 * terminale bout cotee en c. Le bout est un puits : la position est
 * liquidee en c puis c->USD (Convention A).
 *
 * Denombrement : K8 est transitif -> 259 chemins FX se terminent sur
 * chaque noeud ; chacun se prolonge par nbBouts(c) bouts. Total
 * 2072 + 259*26 = 8 806. La forme close du document « 260*C » compte le
 * chemin degenere (0 jambe) par bout — l'enumeration operationnelle du
 * POC/reference n'en a pas (verification annexe H enregistree dans
 * ProofChecks).
 */
public final class ExtPathTables {
    public final int[] fxPi;   // index du prefixe dans PathTables
    public final int[] bout;   // index dans BoutUniverse.BOUTS
    public final int[] start;  // noeud de depart reel (propagation R6)
    public final int[] end;    // devise de cotation du bout (= pEnd du prefixe)

    private ExtPathTables(int[] fxPi, int[] bout, int[] start, int[] end) {
        this.fxPi = fxPi;
        this.bout = bout;
        this.start = start;
        this.end = end;
    }

    public int len() {
        return fxPi.length;
    }

    public static ExtPathTables build(PathTables pt) {
        List<Integer> pi = new ArrayList<>();
        List<Integer> bo = new ArrayList<>();
        for (int p = 0; p < pt.len(); p++) {
            for (int b : BoutUniverse.quotedIn(pt.pEnd[p])) {
                pi.add(p);
                bo.add(b);
            }
        }
        int n = pi.size();
        int[] fxPi = new int[n];
        int[] bout = new int[n];
        int[] start = new int[n];
        int[] end = new int[n];
        for (int i = 0; i < n; i++) {
            fxPi[i] = pi.get(i);
            bout[i] = bo.get(i);
            start[i] = pt.pS[fxPi[i]];
            end[i] = pt.pEnd[fxPi[i]];
        }
        return new ExtPathTables(fxPi, bout, start, end);
    }

    /** 259 chemins FX se terminent par noeud dans K8 (test de preuve). */
    public static int[] endCounts(PathTables pt) {
        int[] cnt = new int[8];
        for (int p = 0; p < pt.len(); p++) {
            cnt[pt.pEnd[p]]++;
        }
        return cnt;
    }
}
