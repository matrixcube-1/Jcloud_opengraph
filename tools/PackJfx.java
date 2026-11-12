import com.dukascopy.api.system.IClient;
import com.dukascopy.api.system.ClientFactory;

import java.io.File;

/**
 * pack_jfx — packe opengraph-jforex-1.0.0.jar en .jfx via l'API JForex.
 *
 * Prérequis : identifiants JForex (DEMO suffit) — packToJfx exige un
 * client connecté au serveur Dukascopy. Le .jfx produit s'importe
 * directement dans JCloud / JForex4 (Remote run).
 *
 * Usage :
 *   javac -cp jforex-api.jar PackJfx.java
 *   java  -cp .;jforex-api.jar PackJfx <jnlpUrl> <user> <pass> [jarPath]
 */
public class PackJfx {

    public static void main(String[] args) throws Exception {
        if (args.length < 3) {
            System.err.println("usage: PackJfx <jnlpUrl> <user> <pass> [jarPath]");
            System.exit(2);
        }
        String jnlp = args[0], user = args[1], pass = args[2];
        File jar = new File(args.length > 3 ? args[3]
                : "target/opengraph-jforex-1.0.0.jar");
        if (!jar.isFile()) {
            System.err.println("jar introuvable : " + jar.getAbsolutePath());
            System.exit(2);
        }

        IClient client = ClientFactory.getDefaultInstance();
        client.connect(jnlp, user, pass);
        try {
            System.out.println("connecte — packToJfx " + jar.getName() + " ...");
            File jfx = client.packToJfx(jar);
            System.out.println("JFX produit : " + jfx.getAbsolutePath());
            System.out.println(" -> import JCloud (espace client) ou "
                    + "Remote run dans JForex4");
        } finally {
            client.disconnect();
        }
    }
}
