package opengraph.core;

import java.util.ArrayList;
import java.util.List;

/**
 * Enumeration des chemins ouverts — DFS sur le graphe des 8 devises.
 * Port de paths.rs — lui-meme port fidele de poc_engine.enum_paths.
 * Un chemin = suite de jambes FX (indices d'aretes), 1 a 3 jambes, paires
 * bidirectionnelles, sans retour sur un noeud deja visite.
 * Resultat attendu : exactement 2 072 chemins.
 */
public final class Paths {

    private Paths() {}

    public static class EnumResult {
        public final List<int[]> paths;
        public final int[] starts;

        EnumResult(List<int[]> p, int[] s) {
            paths = p;
            starts = s;
        }
    }

    /**
     * Enumere tous les chemins ouverts FX purs.
     * starts[i] = noeud de depart reel du DFS — un chemin peut partir du
     * cote quote de sa premiere arete (correctif R6 du POC).
     */
    public static EnumResult enumPaths() {
        // adj[node] = (indice d'arete, autre noeud) — bidirectionnel.
        List<int[]>[] adj = new List[8];
        for (int i = 0; i < 8; i++) {
            adj[i] = new ArrayList<>();
        }
        for (int ei = 0; ei < Universe.FX.length; ei++) {
            int[] ab = Universe.pairNodes(ei);
            adj[ab[0]].add(new int[] {ei, ab[1]});
            adj[ab[1]].add(new int[] {ei, ab[0]});
        }

        List<int[]> paths = new ArrayList<>(2072);
        List<Integer> starts = new ArrayList<>(2072);
        int[] eis = new int[3];
        for (int s0 = 0; s0 < 8; s0++) {
            dfs(s0, 1 << s0, eis, 0, s0, adj, paths, starts);
        }
        int[] st = new int[starts.size()];
        for (int i = 0; i < st.length; i++) {
            st[i] = starts.get(i);
        }
        return new EnumResult(paths, st);
    }

    private static void dfs(int node, int seen, int[] eis, int depth,
                            int s0, List<int[]>[] adj,
                            List<int[]> paths, List<Integer> starts) {
        if (depth > 0) {
            paths.add(java.util.Arrays.copyOf(eis, depth));
            starts.add(s0);
        }
        if (depth >= 3) {
            return;
        }
        for (int[] eb : adj[node]) {
            int ei = eb[0];
            int b = eb[1];
            if ((seen & (1 << b)) == 0) {
                eis[depth] = ei;
                dfs(b, seen | (1 << b), eis, depth + 1, s0, adj,
                        paths, starts);
            }
        }
    }
}
