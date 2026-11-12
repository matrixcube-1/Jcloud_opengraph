package opengraph.validation;

import java.util.ArrayList;
import java.util.List;
import opengraph.core.BoutUniverse;
import opengraph.core.ExtPathTables;
import opengraph.core.PathTables;

/**
 * Preuves machine (Annexe H / §15) : denombrements en forme close vs
 * enumeration reelle. Executees au boot de la strategie (sanity gate).
 */
public final class ProofChecks {

    private ProofChecks() {}

    /** Liste des ecarts ; vide = toutes les preuves passent. */
    public static List<String> run(PathTables pt, ExtPathTables xt) {
        List<String> bad = new ArrayList<>();
        if (pt.len() != 2072) {
            bad.add("chemins FX = " + pt.len() + " != 2072");
        }
        int[] cnt = ExtPathTables.endCounts(pt);
        for (int c = 0; c < 8; c++) {
            if (cnt[c] != 259) {
                bad.add("chemins fin noeud " + c + " = " + cnt[c]
                        + " != 259");
            }
        }
        int expect = 259 * BoutUniverse.BOUTS.length;
        if (xt.len() != expect) {
            bad.add("chemins bouts = " + xt.len() + " != " + expect);
        }
        if (pt.len() + xt.len() != 8806) {
            bad.add("total = " + (pt.len() + xt.len()) + " != 8806");
        }
        // puits : tout bout cote dans la devise terminale du prefixe
        for (int xi = 0; xi < xt.len(); xi++) {
            if (BoutUniverse.BOUTS[xt.bout[xi]].qc != xt.end[xi]) {
                bad.add("puits viole xi=" + xi);
                break;
            }
        }
        return bad;
    }
}
