package me.spolzer.intrade.listener;

import me.spolzer.intrade.InTradePlugin;
import me.spolzer.intrade.trade.TradeSession;
import org.bukkit.Bukkit;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.entity.PlayerDeathEvent;
import org.bukkit.event.player.PlayerInteractEntityEvent;
import org.bukkit.event.player.PlayerJoinEvent;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.event.player.PlayerRespawnEvent;
import org.bukkit.inventory.EquipmentSlot;

public final class PlayerListener implements Listener {
    private final InTradePlugin plugin;

    public PlayerListener(InTradePlugin plugin) {
        this.plugin = plugin;
    }

    @EventHandler(priority = EventPriority.LOWEST)
    public void onQuit(PlayerQuitEvent event) {
        Player player = event.getPlayer();
        TradeSession session = plugin.trades().of(player);
        if (session != null) session.leave(session.sideOf(player), false);
        plugin.requests().forget(player);
        plugin.stats().forget(player.getUniqueId());
    }

    @EventHandler(priority = EventPriority.LOWEST)
    public void onDeath(PlayerDeathEvent event) {
        Player player = event.getEntity();
        TradeSession session = plugin.trades().of(player);
        if (session != null) session.leave(session.sideOf(player), true);
    }

    @EventHandler
    public void onJoin(PlayerJoinEvent event) {
        Player player = event.getPlayer();
        plugin.stats().load(player.getUniqueId());
        deliverLater(player, 40L);
    }

    @EventHandler
    public void onRespawn(PlayerRespawnEvent event) {
        deliverLater(event.getPlayer(), 5L);
    }

    private void deliverLater(Player player, long delay) {
        Bukkit.getScheduler().runTaskLater(plugin, () -> {
            if (player.isOnline()) plugin.deliverMail(player, false);
        }, delay);
    }

    @EventHandler(ignoreCancelled = true)
    public void onInteract(PlayerInteractEntityEvent event) {
        if (!plugin.settings().sneakClick() || event.getHand() != EquipmentSlot.HAND) return;
        Player player = event.getPlayer();
        if (!player.isSneaking() || !(event.getRightClicked() instanceof Player target) || target.hasMetadata("NPC")) return;
        if (!player.hasPermission("intrade.use") || !player.hasPermission("intrade.sneak")) return;
        event.setCancelled(true);
        plugin.requests().send(player, target);
    }
}
