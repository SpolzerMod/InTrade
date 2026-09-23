package me.spolzer.intrade;

import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.stream.Collectors;
import me.spolzer.intrade.api.InTrade;
import me.spolzer.intrade.api.TraderStats;
import me.spolzer.intrade.currency.Currency;
import org.bukkit.entity.Player;

final class TradeService implements InTrade {
    private final InTradePlugin plugin;

    TradeService(InTradePlugin plugin) {
        this.plugin = plugin;
    }

    @Override
    public boolean isTrading(Player player) {
        return plugin.trades().inTrade(player);
    }

    @Override
    public CompletableFuture<Integer> tradeCount(UUID player) {
        return plugin.storage().tradeCount(player);
    }

    @Override
    public CompletableFuture<List<TraderStats>> topTraders(int limit) {
        return plugin.storage().topTraders(limit);
    }

    @Override
    public Set<String> currencies() {
        return plugin.currencies().active().stream().map(Currency::id).collect(Collectors.toUnmodifiableSet());
    }
}
