package me.spolzer.intrade.command;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;
import java.util.logging.Level;
import me.spolzer.intrade.InTradePlugin;
import me.spolzer.intrade.menu.HistoryMenu;
import me.spolzer.intrade.text.Arg;
import org.bukkit.Bukkit;
import org.bukkit.OfflinePlayer;
import org.bukkit.command.Command;
import org.bukkit.command.CommandSender;
import org.bukkit.command.TabExecutor;
import org.bukkit.entity.Player;
import org.jetbrains.annotations.NotNull;

public final class TradeCommand implements TabExecutor {
    private static final Map<String, String> ACTIONS = Map.of(
            "accept", "intrade.use", "deny", "intrade.use", "toggle", "intrade.toggle", "claim", "intrade.use",
            "history", "intrade.history", "reload", "intrade.reload");

    private final InTradePlugin plugin;

    public TradeCommand(InTradePlugin plugin) {
        this.plugin = plugin;
    }

    @Override
    public boolean onCommand(@NotNull CommandSender sender, @NotNull Command command, @NotNull String label, String @NotNull [] args) {
        if (args.length == 0) {
            plugin.messages().send(sender, "command.help");
            return true;
        }
        String action = args[0].toLowerCase(Locale.ROOT);
        if (action.equals("reload")) {
            if (!allowed(sender, "intrade.reload")) return true;
            plugin.reload().whenCompleteAsync((result, error) -> {
                if (error != null) plugin.getLogger().log(Level.SEVERE, "Could not reload InTrade", error);
                plugin.messages().send(sender, error == null ? "command.reloaded" : "command.reload-failed");
            }, plugin.mainThread());
            return true;
        }
        if (!(sender instanceof Player player)) {
            plugin.messages().send(sender, "command.players-only");
            return true;
        }
        switch (action) {
            case "accept" -> {
                if (allowed(player, "intrade.use")) plugin.requests().accept(player, optionalTarget(player, args));
            }
            case "deny" -> {
                if (allowed(player, "intrade.use")) plugin.requests().deny(player, optionalTarget(player, args));
            }
            case "toggle" -> {
                if (allowed(player, "intrade.toggle")) {
                    plugin.messages().send(player, plugin.requests().toggle(player) ? "command.toggle-on" : "command.toggle-off");
                }
            }
            case "claim" -> {
                if (allowed(player, "intrade.use")) plugin.deliverMail(player, true);
            }
            case "history" -> history(player, args);
            default -> {
                if (!allowed(player, "intrade.use")) return true;
                Player target = online(player, args[0]);
                if (target != null) plugin.requests().send(player, target);
            }
        }
        return true;
    }

    private void history(Player player, String[] args) {
        // Staff can view other players' history without the player permissions
        if (!allowed(player, args.length < 2 ? "intrade.history" : "intrade.history.others")) return;
        if (!plugin.settings().historyEnabled()) {
            plugin.messages().send(player, "history.disabled");
            return;
        }
        if (args.length < 2) {
            HistoryMenu.open(plugin, player, player.getUniqueId(), player.getName(), 0);
            return;
        }
        String name = args[1];
        Player online = Bukkit.getPlayerExact(name);
        if (online != null) {
            HistoryMenu.open(plugin, player, online.getUniqueId(), online.getName(), 0);
            return;
        }
        OfflinePlayer cached = Bukkit.getOfflinePlayerIfCached(name);
        if (cached != null) {
            HistoryMenu.open(plugin, player, cached.getUniqueId(), cached.getName() != null ? cached.getName() : name, 0);
            return;
        }
        plugin.storage().findPlayer(name).thenAcceptAsync(id -> openFound(player, id, name), plugin.mainThread());
    }

    private void openFound(Player player, UUID id, String name) {
        if (!player.isOnline()) return;
        if (id == null) plugin.messages().send(player, "command.player-not-found", Arg.of("player", name));
        else HistoryMenu.open(plugin, player, id, name, 0);
    }

    private Player optionalTarget(Player player, String[] args) {
        return args.length < 2 ? null : online(player, args[1]);
    }

    private Player online(Player player, String name) {
        Player target = Bukkit.getPlayerExact(name);
        if (target != null && player.canSee(target)) return target;
        plugin.messages().send(player, "command.player-not-found", Arg.of("player", name));
        return null;
    }

    private boolean allowed(CommandSender sender, String permission) {
        if (sender.hasPermission(permission)) return true;
        plugin.messages().send(sender, "command.no-permission");
        return false;
    }

    @Override
    public List<String> onTabComplete(@NotNull CommandSender sender, @NotNull Command command, @NotNull String alias, String @NotNull [] args) {
        List<String> out = new ArrayList<>();
        String typed = args[args.length - 1].toLowerCase(Locale.ROOT);
        if (args.length == 1) {
            ACTIONS.forEach((action, permission) -> {
                boolean history = action.equals("history") && sender.hasPermission("intrade.history.others");
                if (action.startsWith(typed) && (sender.hasPermission(permission) || history)) out.add(action);
            });
            out.sort(null);
            if (sender.hasPermission("intrade.use")) players(sender, typed, out);
        } else if (args.length == 2 && List.of("accept", "deny", "history").contains(args[0].toLowerCase(Locale.ROOT))) {
            players(sender, typed, out);
        }
        return out;
    }

    private static void players(CommandSender sender, String typed, List<String> out) {
        for (Player online : Bukkit.getOnlinePlayers()) {
            if (online.equals(sender) || sender instanceof Player self && !self.canSee(online)) continue;
            if (online.getName().toLowerCase(Locale.ROOT).startsWith(typed)) out.add(online.getName());
        }
    }
}
