package me.spolzer.intrade.trade;

import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.logging.Level;
import me.spolzer.intrade.InTradePlugin;
import me.spolzer.intrade.api.event.TradeStartEvent;
import me.spolzer.intrade.currency.Currency;
import org.bukkit.Bukkit;
import org.bukkit.entity.Player;

public final class TradeManager {
    private final InTradePlugin plugin;
    private final Map<UUID, TradeSession> sessions = new HashMap<>();

    public TradeManager(InTradePlugin plugin) {
        this.plugin = plugin;
    }

    public TradeSession of(Player player) { return sessions.get(player.getUniqueId()); }
    public boolean inTrade(Player player) { return sessions.containsKey(player.getUniqueId()); }

    void start(Player first, Player second) {
        TradeStartEvent event = new TradeStartEvent(first, second);
        Bukkit.getPluginManager().callEvent(event);
        if (event.isCancelled()) return;
        TradeSession session = new TradeSession(plugin, first, second);
        sessions.put(first.getUniqueId(), session);
        sessions.put(second.getUniqueId(), session);
        session.open();
    }

    void remove(TradeSession session) {
        sessions.values().removeIf(existing -> existing == session);
    }

    // Each session handles its own failures, the catch only keeps one broken session from stopping the others
    public void tick() {
        for (TradeSession session : new LinkedHashSet<>(sessions.values())) {
            try {
                session.tick();
            } catch (RuntimeException e) {
                plugin.getLogger().log(Level.SEVERE, "Could not update a trade", e);
            }
        }
    }

    /** Called after the currencies were loaded again, {@code before} holds the ones that were active until then. */
    public void currenciesReloaded(List<Currency> before) {
        for (TradeSession session : new LinkedHashSet<>(sessions.values())) session.dropDisabledCurrencies(before);
    }

    public void shutdown() {
        for (TradeSession session : new LinkedHashSet<>(sessions.values())) session.shutdown();
    }
}
