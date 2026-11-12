package opengraph.regime;

import java.util.Arrays;

/**
 * Algebre lineaire 8x8 interne — remplace nalgebra (zero dependance).
 * Operations limitees a ce que le regime utilise : Cholesky SPD, eigen
 * symetrique par Jacobi cyclique (deterministe), produits matriciels.
 */
public final class LinAlg {

    private LinAlg() {}

    public static double[][] zeros() {
        return new double[8][8];
    }

    public static double[][] identity() {
        double[][] m = zeros();
        for (int i = 0; i < 8; i++) {
            m[i][i] = 1.0;
        }
        return m;
    }

    /** Copie profonde. */
    public static double[][] copy(double[][] a) {
        double[][] c = zeros();
        for (int i = 0; i < 8; i++) {
            System.arraycopy(a[i], 0, c[i], 0, 8);
        }
        return c;
    }

    /** a + b element par element. */
    public static double[][] add(double[][] a, double[][] b) {
        double[][] c = zeros();
        for (int i = 0; i < 8; i++) {
            for (int j = 0; j < 8; j++) {
                c[i][j] = a[i][j] + b[i][j];
            }
        }
        return c;
    }

    /** a - b element par element. */
    public static double[][] sub(double[][] a, double[][] b) {
        double[][] c = zeros();
        for (int i = 0; i < 8; i++) {
            for (int j = 0; j < 8; j++) {
                c[i][j] = a[i][j] - b[i][j];
            }
        }
        return c;
    }

    /** a * b produit matriciel. */
    public static double[][] mul(double[][] a, double[][] b) {
        double[][] c = zeros();
        for (int i = 0; i < 8; i++) {
            for (int k = 0; k < 8; k++) {
                double aik = a[i][k];
                if (aik == 0.0) {
                    continue;
                }
                for (int j = 0; j < 8; j++) {
                    c[i][j] += aik * b[k][j];
                }
            }
        }
        return c;
    }

    /** a * scalaire. */
    public static double[][] scale(double[][] a, double s) {
        double[][] c = zeros();
        for (int i = 0; i < 8; i++) {
            for (int j = 0; j < 8; j++) {
                c[i][j] = a[i][j] * s;
            }
        }
        return c;
    }

    /** a^T. */
    public static double[][] transpose(double[][] a) {
        double[][] c = zeros();
        for (int i = 0; i < 8; i++) {
            for (int j = 0; j < 8; j++) {
                c[i][j] = a[j][i];
            }
        }
        return c;
    }

    /** Norme de Frobenius. */
    public static double norm(double[][] a) {
        double s = 0.0;
        for (int i = 0; i < 8; i++) {
            for (int j = 0; j < 8; j++) {
                s += a[i][j] * a[i][j];
            }
        }
        return Math.sqrt(s);
    }

    /** Norme euclidienne d'un vecteur. */
    public static double norm(double[] v) {
        double s = 0.0;
        for (double x : v) {
            s += x * x;
        }
        return Math.sqrt(s);
    }

    /** Matrice de fonction de position : m[i][j] = f(i,j). */
    public static double[][] fromFn(java.util.function.BiFunction<Integer, Integer, Double> f) {
        double[][] m = zeros();
        for (int i = 0; i < 8; i++) {
            for (int j = 0; j < 8; j++) {
                m[i][j] = f.apply(i, j);
            }
        }
        return m;
    }

    /** Produit dyadique v * v^T. */
    public static double[][] outer(double[] v) {
        double[][] m = zeros();
        for (int i = 0; i < 8; i++) {
            for (int j = 0; j < 8; j++) {
                m[i][j] = v[i] * v[j];
            }
        }
        return m;
    }

    /**
     * Resout A x = b par Cholesky pour A symetrique definie positive.
     * Retourne null si non SPD (pivot <= 0 ou non fini).
     */
    public static double[] choleskySolve(double[][] a, double[] b) {
        double[][] l = zeros();
        for (int i = 0; i < 8; i++) {
            for (int j = 0; j <= i; j++) {
                double s = a[i][j];
                for (int k = 0; k < j; k++) {
                    s -= l[i][k] * l[j][k];
                }
                if (i == j) {
                    if (!(s > 0.0) || !Double.isFinite(s)) {
                        return null;
                    }
                    l[i][j] = Math.sqrt(s);
                } else {
                    l[i][j] = s / l[j][j];
                }
            }
        }
        // descente L y = b
        double[] y = new double[8];
        for (int i = 0; i < 8; i++) {
            double s = b[i];
            for (int k = 0; k < i; k++) {
                s -= l[i][k] * y[k];
            }
            y[i] = s / l[i][i];
        }
        // remontee L^T x = y
        double[] x = new double[8];
        for (int i = 7; i >= 0; i--) {
            double s = y[i];
            for (int k = i + 1; k < 8; k++) {
                s -= l[k][i] * x[k];
            }
            x[i] = s / l[i][i];
        }
        return x;
    }

    /** Resultat spectral : valeurs propres + vecteurs (colonnes). */
    public static final class Eig {
        /** Valeurs propres triees DECROISSANT (departage par indice). */
        public final double[] values;
        /** Vecteurs propres en colonnes : vectors[i][k] = composante i du
         * k-ieme vecteur propre (ordre = values). */
        public final double[][] vectors;

        Eig(double[] values, double[][] vectors) {
            this.values = values;
            this.vectors = vectors;
        }
    }

    /**
     * Decomposition spectrale d'une matrice symetrique par rotations de
     * Jacobi cycliques (sweeps jusqu'a convergence, hors-diagonale < tol).
     * Tri decroissant + departage par indice (miroir du tri explicite du
     * Rust pour le determinisme).
     */
    public static Eig jacobiEigen(double[][] a, double tol, int maxSweeps) {
        double[][] m = copy(a);
        double[][] v = identity();
        for (int sweep = 0; sweep < maxSweeps; sweep++) {
            // off-diagonal max
            double off = 0.0;
            for (int p = 0; p < 8; p++) {
                for (int q = p + 1; q < 8; q++) {
                    off = Math.max(off, Math.abs(m[p][q]));
                }
            }
            if (off < tol) {
                break;
            }
            for (int p = 0; p < 8; p++) {
                for (int q = p + 1; q < 8; q++) {
                    if (Math.abs(m[p][q]) < tol * 0.01) {
                        continue;
                    }
                    // rotation de Jacobi J(p,q,theta)
                    double app = m[p][p];
                    double aqq = m[q][q];
                    double apq = m[p][q];
                    double tau = (aqq - app) / (2.0 * apq);
                    double t = Math.signum(tau) == 0.0 ? 1.0
                            : Math.signum(tau)
                                    / (Math.abs(tau) + Math.sqrt(1.0 + tau * tau));
                    double c = 1.0 / Math.sqrt(1.0 + t * t);
                    double s = t * c;
                    // m = J^T m J
                    for (int k = 0; k < 8; k++) {
                        double mkp = m[k][p];
                        double mkq = m[k][q];
                        m[k][p] = c * mkp - s * mkq;
                        m[k][q] = s * mkp + c * mkq;
                    }
                    for (int k = 0; k < 8; k++) {
                        double mpk = m[p][k];
                        double mqk = m[q][k];
                        m[p][k] = c * mpk - s * mqk;
                        m[q][k] = s * mpk + c * mqk;
                    }
                    for (int k = 0; k < 8; k++) {
                        double vkp = v[k][p];
                        double vkq = v[k][q];
                        v[k][p] = c * vkp - s * vkq;
                        v[k][q] = s * vkp + c * vkq;
                    }
                }
            }
        }
        // eigenvalues = diagonale ; eigenvectors = colonnes de v
        double[] eig = new double[8];
        for (int i = 0; i < 8; i++) {
            eig[i] = m[i][i];
        }
        // tri decroissant + departage par indice (deterministe)
        Integer[] idx = new Integer[8];
        for (int i = 0; i < 8; i++) {
            idx[i] = i;
        }
        Arrays.sort(idx, (a1, b1) -> {
            int c = Double.compare(eig[b1], eig[a1]);
            return c != 0 ? c : Integer.compare(a1, b1);
        });
        double[] eigSorted = new double[8];
        double[][] vSorted = zeros();
        for (int k = 0; k < 8; k++) {
            eigSorted[k] = eig[idx[k]];
            for (int i = 0; i < 8; i++) {
                vSorted[i][k] = v[i][idx[k]];
            }
        }
        return new Eig(eigSorted, vSorted);
    }
}
