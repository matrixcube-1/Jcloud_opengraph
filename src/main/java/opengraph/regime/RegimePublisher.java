package opengraph.regime;

import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;

/**
 * Editeur : publie un instantane par echange atomique de reference —
 * equivalent Java d'arc_swap::ArcSwap (lecture lock-free du chemin chaud).
 */
public final class RegimePublisher {
    private final AtomicReference<RegimeSnapshot> slot;
    private final AtomicLong seq = new AtomicLong(0);

    public RegimePublisher(RegimeSnapshot initial) {
        this.slot = new AtomicReference<>(initial);
    }

    /** Publie : ecrase le seq par le compteur interne +1 (strict. croissant). */
    public long publish(RegimeSnapshot snap) {
        long s = seq.incrementAndGet();
        slot.set(new RegimeSnapshot(s, snap.eventTimeNs, snap.gateOpen,
                snap.tTilde, snap.dominance, snap.dominant, snap.direction));
        return s;
    }

    /** Lecture sans verrou (chemin chaud). */
    public RegimeSnapshot load() {
        return slot.get();
    }
}
