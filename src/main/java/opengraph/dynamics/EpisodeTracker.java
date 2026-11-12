package opengraph.dynamics;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * <b>DORMANT</b> — utilise en tests uniquement ; non cable au pipeline.
 * Episodes d'opportunite (§10/E9) : ouvert quand un candidat depasse le
 * seuil, ferme quand le signal retombe. Sortie = entree du fit Hawkes
 * (durees de vie des desequilibres) et des metriques §15.2.
 */
public final class EpisodeTracker {

    public static final class Episode {
        public final long tOpen, tClose;
        public final double maxNet;
        public final int path;

        Episode(long tOpen, long tClose, double maxNet, int path) {
            this.tOpen = tOpen;
            this.tClose = tClose;
            this.maxNet = maxNet;
            this.path = path;
        }
    }

    private static final class Open {
        long tOpen, tLast;
        double maxNet;
    }

    private final double openThresh;
    private final Map<Integer, Open> open = new HashMap<>();
    private final Deque<Episode> closed = new ArrayDeque<>();

    /** openThresh = net minimum declenchant un episode. */
    public EpisodeTracker(double openThresh) {
        this.openThresh = openThresh;
    }

    /**
     * Alimente le tracker : netUsd = meilleur net evalue a t (NaN ou <=
     * seuil = pas de signal -> ferme tout). Retourne les episodes fermes.
     */
    public List<Episode> feed(long t, double netUsd, int pathIdx) {
        if (Double.isFinite(netUsd) && netUsd > openThresh) {
            Open o = open.get(pathIdx);
            if (o == null) {
                o = new Open();
                o.tOpen = t;
                o.maxNet = netUsd;
                open.put(pathIdx, o);
            }
            o.tLast = t;
            o.maxNet = Math.max(o.maxNet, netUsd);
            return List.of();
        }
        // pas de signal : tout ouvert se ferme a t
        List<Episode> out = new ArrayList<>(open.size());
        for (Map.Entry<Integer, Open> e : open.entrySet()) {
            Episode ep = new Episode(e.getValue().tOpen, t,
                    e.getValue().maxNet, e.getKey());
            closed.addLast(ep);
            out.add(ep);
        }
        open.clear();
        return out;
    }

    /** Vide les episodes fermes accumules. */
    public List<Episode> drain() {
        List<Episode> out = new ArrayList<>(closed);
        closed.clear();
        return out;
    }

    public int openCount() {
        return open.size();
    }
}
