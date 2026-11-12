package opengraph.core;

import static org.junit.jupiter.api.Assertions.*;

import com.dukascopy.api.Instrument;
import java.util.Arrays;
import java.util.HashSet;
import java.util.Set;
import org.junit.jupiter.api.Test;

/** Noms JForex reels (jforex-api 2.13.99) pour les 28 FX et 26 bouts. */
class JfxNamesTest {

    /** Gamme officielle Dukascopy, non predefinis statiquement (fromString en ligne). */
    static final Set<String> DYNAMIC = new HashSet<>(Arrays.asList(
            "USSC2000IDXUSD", "XPTCMDUSD", "XPDCMDUSD"));

    @Test
    void all_28_fx_resolve_offline() {
        for (String p : Universe.FX) {
            Instrument i = Instrument.fromString(p.substring(0, 3) + "/" + p.substring(3));
            assertNotNull(i, p);
            assertEquals(p.substring(0, 3), i.getPrimaryJFCurrency().getCurrencyCode(), p);
        }
    }

    @Test
    void tradable_static_bouts_resolve_to_their_jfx_name() {
        int n = 0;
        for (int b = 0; b < BoutUniverse.BOUTS.length; b++) {
            Bout bt = BoutUniverse.BOUTS[b];
            if (!BoutUniverse.jfxTradable(b) || DYNAMIC.contains(bt.name)) {
                continue;
            }
            Instrument i = Instrument.fromString(bt.jfx);
            assertNotNull(i, bt.name + " -> " + bt.jfx);
            assertEquals(bt.jfx, i.toString());
            n++;
        }
        assertEquals(16, n);
        assertEquals(19, n + DYNAMIC.size());
    }

    @Test
    void metal_crosses_are_flagged_non_tradable() {
        Set<String> nt = new HashSet<>();
        for (int b = 0; b < BoutUniverse.BOUTS.length; b++) {
            if (!BoutUniverse.jfxTradable(b)) {
                nt.add(BoutUniverse.BOUTS[b].name);
                assertNull(Instrument.fromString(BoutUniverse.BOUTS[b].jfx));
            }
        }
        assertEquals(new HashSet<>(Arrays.asList("XAUEUR", "XAGEUR", "XAUGBP",
                "XAUJPY", "XAUCHF", "XAUAUD", "XAGAUD")), nt);
    }
}
