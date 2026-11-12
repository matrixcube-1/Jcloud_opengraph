package opengraph.core;

import java.util.ArrayList;
import java.util.List;

/**
 * Tables precompilees des chemins — miroir de build_tables du POC/Rust.
 *
 * Pour chaque chemin pi et chaque jambe li < pL[pi] :
 * - pE    : indice d'arete FX (0..28)
 * - pDir  : +1 si le noeud courant est la base (vente au bid), -1 sinon
 * - pBase : indice de la devise de base (commission)
 */
public final class PathTables {
    public final List<int[]> paths;
    public final int[] pS;
    public final int[] pEnd;
    public final int[] pL;
    public final int[][] pE;
    public final int[][] pDir;
    public final int[][] pBase;

    private PathTables(List<int[]> paths, int[] pS, int[] pEnd, int[] pL,
                       int[][] pE, int[][] pDir, int[][] pBase) {
        this.paths = paths;
        this.pS = pS;
        this.pEnd = pEnd;
        this.pL = pL;
        this.pE = pE;
        this.pDir = pDir;
        this.pBase = pBase;
    }

    public int len() {
        return paths.size();
    }

    /**
     * Construit les tables par propagation de noeud — identique au POC :
     * d = +1 si node == base, sinon -1 ; node = autre cote.
     */
    public static PathTables build() {
        Paths.EnumResult er = Paths.enumPaths();
        int n = er.paths.size();
        int[] pS = new int[n];
        int[] pEnd = new int[n];
        int[] pL = new int[n];
        int[][] pE = new int[n][3];
        int[][] pDir = new int[n][3];
        int[][] pBase = new int[n][3];
        for (int pi = 0; pi < n; pi++) {
            for (int li = 0; li < 3; li++) {
                pE[pi][li] = -1;
                pDir[pi][li] = 1;
                pBase[pi][li] = -1;
            }
        }
        for (int pi = 0; pi < n; pi++) {
            int[] path = er.paths.get(pi);
            int node = er.starts[pi];
            pS[pi] = node;
            for (int li = 0; li < path.length; li++) {
                int e = path[li];
                int[] ab = Universe.pairNodes(e);
                if (node != ab[0] && node != ab[1]) {
                    throw new IllegalStateException(
                            "buildTables : noeud " + node + " hors arete ("
                                    + ab[0] + "," + ab[1] + ")");
                }
                int d = node == ab[0] ? 1 : -1;
                pDir[pi][li] = d;
                pE[pi][li] = e;
                pBase[pi][li] = ab[0];
                node = d == 1 ? ab[1] : ab[0];
                pEnd[pi] = node;
            }
            pL[pi] = path.length;
        }
        return new PathTables(er.paths, pS, pEnd, pL, pE, pDir, pBase);
    }

    /** Sous-ensemble de chemins : garde pL <= maxLegs. Les index pi du
     *  resultat sont reindexes (eval/exec utilisent ce sous-ensemble). */
    public static PathTables maxLegs(PathTables src, int maxLegs) {
        List<int[]> paths2 = new ArrayList<>();
        List<Integer> keep = new ArrayList<>();
        for (int pi = 0; pi < src.len(); pi++) {
            if (src.pL[pi] <= maxLegs) {
                keep.add(pi);
                paths2.add(src.paths.get(pi));
            }
        }
        int n = keep.size();
        int[] pS2 = new int[n];
        int[] pEnd2 = new int[n];
        int[] pL2 = new int[n];
        int[][] pE2 = new int[n][3];
        int[][] pDir2 = new int[n][3];
        int[][] pBase2 = new int[n][3];
        for (int i = 0; i < n; i++) {
            int pi = keep.get(i);
            pS2[i] = src.pS[pi];
            pEnd2[i] = src.pEnd[pi];
            pL2[i] = src.pL[pi];
            pE2[i] = src.pE[pi];
            pDir2[i] = src.pDir[pi];
            pBase2[i] = src.pBase[pi];
        }
        return new PathTables(paths2, pS2, pEnd2, pL2, pE2, pDir2, pBase2);
    }
}
