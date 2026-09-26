package me.spolzer.intrade.api;

import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import org.bukkit.Bukkit;
import org.bukkit.entity.Player;
import org.bukkit.plugin.RegisteredServiceProvider;

/**
 * Entry point of the InTrade API.
 *
 * <p>Futures are completed on a database thread. Switch to the main server thread before
 * using the Bukkit API in their callbacks.
 */
public interface InTrade {

    /**
     * Returns the running InTrade instance.
     *
     * @throws IllegalStateException if InTrade is not enabled
     */
    static InTrade get() {
        RegisteredServiceProvider<InTrade> provider = Bukkit.getServicesManager().getRegistration(InTrade.class);
        if (provider == null) throw new IllegalStateException("InTrade is not enabled");
        return provider.getProvider();
    }

    /** Whether the player has an open trade window. Main thread only. */
    boolean isTrading(Player player);

    /** Number of completed trades of the player. Not affected by history cleanup. */
    CompletableFuture<Integer> tradeCount(UUID player);

    /** Players with the most completed trades, best first. */
    CompletableFuture<List<TraderStats>> topTraders(int limit);

    /** Ids of the enabled currencies, for example {@code money}, {@code experience}, {@code playerpoints}. */
    Set<String> currencies();
}
