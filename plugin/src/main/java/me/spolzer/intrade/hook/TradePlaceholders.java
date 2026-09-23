package me.spolzer.intrade.hook;

import java.util.List;
import me.clip.placeholderapi.expansion.PlaceholderExpansion;
import me.spolzer.intrade.InTradePlugin;
import me.spolzer.intrade.api.TraderStats;
import org.bukkit.OfflinePlayer;
import org.bukkit.entity.Player;
import org.jetbrains.annotations.NotNull;

public final class TradePlaceholders extends PlaceholderExpansion {
    private final InTradePlugin plugin;

    public TradePlaceholders(InTradePlugin plugin) {
        this.plugin = plugin;
    }

    @Override public @NotNull String getIdentifier() { return "intrade"; }
    @Override public @NotNull String getAuthor() { return "Spolzer"; }
    @Override public @NotNull String getVersion() { return plugin.getPluginMeta().getVersion(); }
    @Override public boolean persist() { return true; }

    @Override
    public String onRequest(OfflinePlayer player, @NotNull String params) {
        if (params.startsWith("top_")) return top(params);
        if (player == null) return "";
        return switch (params) {
            case "trades" -> Integer.toString(plugin.stats().count(player.getUniqueId()));
            case "trading" -> Boolean.toString(player instanceof Player online && plugin.trades().inTrade(online));
            case "accepting" -> Boolean.toString(!(player instanceof Player online) || plugin.requests().accepting(online));
            default -> null;
        };
    }

    private String top(String params) {
        String[] parts = params.split("_");
        if (parts.length != 3) return null;
        int place;
        try {
            place = Integer.parseInt(parts[1]);
        } catch (NumberFormatException e) {
            return null;
        }
        List<TraderStats> top = plugin.stats().top();
        TraderStats entry = place >= 1 && place <= top.size() ? top.get(place - 1) : null;
        return switch (parts[2]) {
            case "name" -> entry == null ? "-" : entry.name();
            case "trades" -> entry == null ? "0" : Integer.toString(entry.trades());
            default -> null;
        };
    }
}
