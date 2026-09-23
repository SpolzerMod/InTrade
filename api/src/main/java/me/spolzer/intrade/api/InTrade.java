package me.spolzer.intrade.api;

import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import org.bukkit.Bukkit;
import org.bukkit.entity.Player;
import org.bukkit.plugin.RegisteredServiceProvider;

public interface InTrade {

    static InTrade get() {
        RegisteredServiceProvider<InTrade> provider = Bukkit.getServicesManager().getRegistration(InTrade.class);
        if (provider == null) throw new IllegalStateException("InTrade is not enabled");
        return provider.getProvider();
    }

    boolean isTrading(Player player);

    /** Completed trades of the player. Not affected by history cleanup. */
    CompletableFuture<Integer> tradeCount(UUID player);

    CompletableFuture<List<TraderStats>> topTraders(int limit);

    /** Ids of the enabled currencies: money, experience, playerpoints. */
    Set<String> currencies();
}
