package opengraph.regime;

import static org.junit.jupiter.api.Assertions.*;

import org.junit.jupiter.api.Test;

/** Miroirs de tension.rs::tests. */
class TensionTest {

    static double[][] coherentX() {
        double[] v = {0.3, -0.1, 4.9, 0.0, -0.4, 0.3, -0.2, -0.6};
        return LinAlg.fromFn((i, j) -> v[i] - v[j]);
    }

    static double[][] coherentXAt(double[] v) {
        return LinAlg.fromFn((i, j) -> v[i] - v[j]);
    }

    static double[][] k8() {
        return LinAlg.fromFn((i, j) -> i != j ? 1.0 : 0.0);
    }

    static double[][] spreads() {
        return LinAlg.fromFn((i, j) -> i != j ? 0.002 : 1.0);
    }

    static double[][] antisymX() {
        double[][] m = LinAlg.zeros();
        for (int i = 0; i < 8; i++) {
            for (int j = i + 1; j < 8; j++) {
                double s = ((i * 13 + j * 7 + 3) % 5) - 2.0;
                m[i][j] = s * 0.001;
                m[j][i] = -s * 0.001;
            }
        }
        return m;
    }

    // --- potentiel / residu (Prop. 1) ---------------------------------

    /** Prop. 1(iv) : marche coherent => R = 0. */
    @Test
    void coherent_market_gives_zero_residual() {
        double[][] r = Tension.residual(coherentX(), k8());
        assertNotNull(r);
        assertTrue(LinAlg.norm(r) < 1e-12, "R devrait etre nul: "
                + LinAlg.norm(r));
    }

    /** Prop. 1(i) : sur K8, p_i = (1/n) Somme_j x_ij. */
    @Test
    void potential_is_row_mean_on_complete_graph() {
        double[][] x = antisymX();
        double[] p = Tension.potential(x, k8());
        assertNotNull(p);
        for (int i = 0; i < 8; i++) {
            double mean = 0.0;
            for (int j = 0; j < 8; j++) {
                mean += x[i][j];
            }
            mean /= 8.0;
            assertEquals(mean, p[i], 1e-12);
        }
    }

    /** Prop. 1(ii) : R_ij = (1/n) Somme_k c_ijk, c_ijk = x_ij+x_jk+x_ki. */
    @Test
    void residual_equals_cycle_average() {
        double[][] x = antisymX();
        double[][] r = Tension.residual(x, k8());
        assertNotNull(r);
        for (int i = 0; i < 8; i++) {
            for (int j = 0; j < 8; j++) {
                if (i == j) {
                    continue;
                }
                double cAvg = 0.0;
                for (int k = 0; k < 8; k++) {
                    cAvg += x[i][j] + x[j][k] + x[k][i];
                }
                cAvg /= 8.0;
                assertEquals(cAvg, r[i][j], 1e-12);
            }
        }
    }

    /** R antisymetrique et R.1 = 0 (Prop. 1(iii)). */
    @Test
    void residual_is_antisymmetric_and_orthogonal_to_ones() {
        double[][] x = antisymX();
        double[][] r = Tension.residual(x, k8());
        assertNotNull(r);
        double[][] rt = LinAlg.transpose(r);
        assertTrue(LinAlg.norm(LinAlg.add(r, rt)) < 1e-12,
                "R non antisymetrique");
        double r1 = 0.0;
        for (int i = 0; i < 8; i++) {
            double s = 0.0;
            for (int j = 0; j < 8; j++) {
                s += r[i][j];
            }
            r1 += s * s;
        }
        assertTrue(Math.sqrt(r1) < 1e-12, "R1 != 0");
    }

    /** Venue en etoile (arbre) => R = 0. */
    @Test
    void star_venue_has_zero_residual() {
        double[][] edges = LinAlg.zeros();
        for (int j = 1; j < 8; j++) {
            edges[0][j] = 1.0;
            edges[j][0] = 1.0;
        }
        double[][] x = LinAlg.fromFn((i, j) ->
                edges[i][j] > 0.0 ? (((i * 31 + j * 17) % 7)) * 0.001 : 0.0);
        double[][] xt = LinAlg.transpose(x);
        x = LinAlg.sub(x, xt);
        double[][] r = Tension.residual(x, edges);
        assertNotNull(r);
        assertTrue(LinAlg.norm(r) < 1e-12, "arbre => R nul");
    }

    /** Graphe non connexe => potentiel/residu indisponibles (E3). */
    @Test
    void disconnected_graph_is_unavailable() {
        double[][] edges = LinAlg.zeros();
        edges[0][1] = 1.0;
        edges[1][0] = 1.0;
        for (int i = 2; i < 8; i++) {
            for (int j = 2; j < 8; j++) {
                if (i != j) {
                    edges[i][j] = 1.0;
                }
            }
        }
        double[][] x = antisymX();
        assertNull(Tension.residual(x, edges));
        assertNull(Tension.potential(x, edges));
    }

    // --- adjacence / tension ------------------------------------------

    /** Def. 2 : A = |R|/s, nulle hors aretes. */
    @Test
    void adjacency_is_abs_residual_over_spread() {
        double[][] r = LinAlg.zeros();
        r[0][1] = 0.004;
        r[1][0] = -0.004;
        double[][] s = LinAlg.fromFn((i, j) -> 0.002);
        for (int i = 0; i < 8; i++) {
            s[i][i] = 1.0;
        }
        double[][] edges = LinAlg.fromFn((i, j) ->
                (i == 0 && j == 1) || (i == 1 && j == 0) ? 1.0 : 0.0);
        double[][] a = Tension.adjacency(r, s, edges);
        assertEquals(2.0, a[0][1], 1e-12);
        assertEquals(0.0, a[2][3], 1e-12);
    }

    /** Lemme 1 : le spectre de Abar est dans [-1,1] et contient 1. */
    @Test
    void normalized_adjacency_spectrum_in_unit_disk() {
        double[][] r = Tension.residual(antisymX(), k8());
        assertNotNull(r);
        double[][] a = Tension.adjacency(r, spreads(), k8());
        Tension.Normalized nd = Tension.normalize(a, 1e-12);
        LinAlg.Eig eig = LinAlg.jacobiEigen(nd.abar, 1e-18, 100);
        double maxEv = eig.values[0];
        double minEv = eig.values[7];
        assertEquals(1.0, maxEv, 1e-9, "1 doit etre valeur propre");
        assertTrue(minEv >= -1.0 - 1e-9, "spectre subset [-1,1]");
    }

    /** Lemme 1 : Abar(cA) = Abar(A). */
    @Test
    void normalized_adjacency_is_scale_invariant() {
        double[][] r = Tension.residual(antisymX(), k8());
        assertNotNull(r);
        double[][] a = Tension.adjacency(r, spreads(), k8());
        double[][] a1 = Tension.normalize(a, 1e-12).abar;
        double[][] a2 = Tension.normalize(LinAlg.scale(a, 7.3), 1e-12).abar;
        assertTrue(LinAlg.norm(LinAlg.sub(a1, a2)) < 1e-12,
                "Abar doit etre invariant d'echelle");
    }

    /** Horner = puissances explicites : Atilde = Abar + Abar^2 + Abar^3. */
    @Test
    void multi_hop_matches_explicit_powers() {
        double[][] r = Tension.residual(antisymX(), k8());
        assertNotNull(r);
        double[][] a = Tension.adjacency(r, spreads(), k8());
        double[][] abar = Tension.normalize(a, 1e-12).abar;
        double[][] horner = Tension.multiHop(abar, 3);
        double[][] a2 = LinAlg.mul(abar, abar);
        double[][] a3 = LinAlg.mul(a2, abar);
        double[][] explicit = LinAlg.add(LinAlg.add(abar, a2), a3);
        assertTrue(LinAlg.norm(LinAlg.sub(horner, explicit)) < 1e-12);
    }

    /** Eq. (4) : M = A + AD+A + AD+AD+A — identite de-normalisee. */
    @Test
    void denormalized_m_matches_a_plus_walk_sums() {
        double[][] r = Tension.residual(antisymX(), k8());
        assertNotNull(r);
        double[][] a = Tension.adjacency(r, spreads(), k8());
        Tension.Normalized nd = Tension.normalize(a, 1e-12);
        double[][] at = Tension.multiHop(nd.abar, 3);
        double[] sq = new double[8];
        for (int i = 0; i < 8; i++) {
            sq[i] = nd.degrees[i] > 1e-12 ? Math.sqrt(nd.degrees[i]) : 0.0;
        }
        double[][] m = LinAlg.fromFn((i, j) ->
                sq[i] * at[i][j] * sq[j]);
        double[] dp = new double[8];
        for (int i = 0; i < 8; i++) {
            dp[i] = nd.degrees[i] > 1e-12 ? 1.0 / nd.degrees[i] : 0.0;
        }
        double[][] ddiag = LinAlg.fromFn((i, j) -> i.equals(j) ? dp[i] : 0.0);
        double[][] adA = LinAlg.mul(LinAlg.mul(a, ddiag), a);
        double[][] adAdA = LinAlg.mul(adA, LinAlg.mul(ddiag, a));
        double[][] mRef = LinAlg.add(LinAlg.add(a, adA), adAdA);
        assertTrue(LinAlg.norm(LinAlg.sub(m, mRef)) < 1e-10,
                "identite (4)");
    }

    /** Prop. 2(i) : T = 0 <=> A = 0. */
    @Test
    void zero_adjacency_gives_zero_tension() {
        assertEquals(0.0, Tension.tensionFromAdjacency(
                LinAlg.zeros(), 3, 1e-12));
    }

    /** Prop. 2(ii) : T(cA) = c.T(A). */
    @Test
    void tension_is_homogeneous_degree_one() {
        double[][] r = Tension.residual(antisymX(), k8());
        assertNotNull(r);
        double[][] a = Tension.adjacency(r, spreads(), k8());
        double t1 = Tension.tensionFromAdjacency(a, 3, 1e-12);
        double t2 = Tension.tensionFromAdjacency(
                LinAlg.scale(a, 7.3), 3, 1e-12);
        assertEquals(7.3, t2 / t1, 1e-9);
    }

    /** Prop. 2(iii) : T invariant par permutation des devises. */
    @Test
    void tension_is_permutation_invariant() {
        double[][] r = Tension.residual(antisymX(), k8());
        assertNotNull(r);
        double[][] a = Tension.adjacency(r, spreads(), k8());
        int[] perm = {3, 0, 7, 1, 5, 2, 6, 4};
        double[][] ap = LinAlg.fromFn((i, j) -> a[perm[i]][perm[j]]);
        double t1 = Tension.tensionFromAdjacency(a, 3, 1e-12);
        double t2 = Tension.tensionFromAdjacency(ap, 3, 1e-12);
        assertEquals(t1, t2, 1e-12);
    }

    /** Garde : degre nul (devise isolee) ne doit pas produire NaN. */
    @Test
    void zero_degree_guard_no_nan() {
        double[][] a = LinAlg.zeros();
        a[0][1] = 1.0;
        a[1][0] = 1.0;
        double t = Tension.tensionFromAdjacency(a, 3, 1e-12);
        assertTrue(Double.isFinite(t), "T doit etre fini");
        assertTrue(t > 0.0);
    }
}
