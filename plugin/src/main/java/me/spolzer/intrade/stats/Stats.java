package me.spolzer.intrade.stats;

import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executor;
import me.spolzer.intrade.api.TraderStats;
import me.spolzer.intrade.storage.TradeStorage;
import org.bukkit.Bukkit;

/** Cached trade counts of online players and the trader top, for placeholders. */
public final class Stats {
    public static final int TOP_SIZE = 10;

    private final TradeStorage storage;
    private final Executor mainThread;
    // Read by PlaceholderAPI, which may run on other threads
    private final Map<UUID, Integer> counts = new ConcurrentHashMap<>();
    private volatile List<TraderStats> top = List.of();

    public Stats(TradeStorage storage, Executor mainThread) {
        this.storage = storage;
        this.mainThread = mainThread;
    }

    public void load(UUID player) {
        storage.tradeCount(player).thenAcceptAsync(count -> {
            // The player may have left while the count was loading
            if (Bukkit.getPlayer(player) != null) counts.put(player, count);
        }, mainThread);
    }

    public void forget(UUID player) {
        counts.remove(player);
    }

    /** Called once the trade is written, so the counts are read back already including it. */
    public void traded(UUID a, UUID b) {
        load(a);
        load(b);
        refreshTop();
    }

    public void refreshTop() {
        storage.topTraders(TOP_SIZE).thenAccept(list -> top = list);
    }

    public int count(UUID player) { return counts.getOrDefault(player, 0); }
    public List<TraderStats> top() { return top; }
}
