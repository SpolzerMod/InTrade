package me.spolzer.intrade.stats;

import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import me.spolzer.intrade.api.TraderStats;
import me.spolzer.intrade.storage.TradeStorage;

public final class Stats {
    public static final int TOP_SIZE = 10;

    private final TradeStorage storage;
    private final Map<UUID, Integer> counts = new ConcurrentHashMap<>();
    private volatile List<TraderStats> top = List.of();

    public Stats(TradeStorage storage) {
        this.storage = storage;
    }

    public void load(UUID player) {
        storage.tradeCount(player).thenAccept(count -> counts.put(player, count));
    }

    public void forget(UUID player) {
        counts.remove(player);
    }

    public void completed(UUID a, UUID b) {
        counts.merge(a, 1, Integer::sum);
        counts.merge(b, 1, Integer::sum);
    }

    public void refreshTop() {
        storage.topTraders(TOP_SIZE).thenAccept(list -> top = list);
    }

    public int count(UUID player) { return counts.getOrDefault(player, 0); }
    public List<TraderStats> top() { return top; }
}
