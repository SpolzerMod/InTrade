package me.spolzer.intrade.listener;

import me.spolzer.intrade.menu.HistoryDetailMenu;
import me.spolzer.intrade.menu.HistoryMenu;
import me.spolzer.intrade.trade.PreviewMenu;
import me.spolzer.intrade.trade.TradeMenu;
import me.spolzer.intrade.trade.TradeSide;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.inventory.InventoryClickEvent;
import org.bukkit.event.inventory.InventoryCloseEvent;
import org.bukkit.event.inventory.InventoryDragEvent;
import org.bukkit.inventory.InventoryHolder;

public final class MenuListener implements Listener {

    @EventHandler(priority = EventPriority.LOWEST)
    public void onClick(InventoryClickEvent event) {
        InventoryHolder holder = event.getInventory().getHolder();
        if (holder instanceof TradeMenu menu) menu.handleClick(event);
        else if (holder instanceof HistoryMenu menu) menu.handleClick(event);
        else if (holder instanceof HistoryDetailMenu menu) menu.handleClick(event);
        else if (holder instanceof PreviewMenu) event.setCancelled(true);
    }

    @EventHandler(priority = EventPriority.LOWEST)
    public void onDrag(InventoryDragEvent event) {
        InventoryHolder holder = event.getInventory().getHolder();
        if (holder instanceof TradeMenu || holder instanceof PreviewMenu
                || holder instanceof HistoryMenu || holder instanceof HistoryDetailMenu) {
            event.setCancelled(true);
        }
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onClose(InventoryCloseEvent event) {
        InventoryHolder holder = event.getInventory().getHolder();
        if (holder instanceof TradeMenu menu) {
            // Quits and deaths are handled in PlayerListener
            InventoryCloseEvent.Reason reason = event.getReason();
            if (reason == InventoryCloseEvent.Reason.DISCONNECT || reason == InventoryCloseEvent.Reason.DEATH) return;
            if (!menu.session().finished() && menu.side().mode() == TradeSide.Mode.MENU) menu.session().cancel(menu.side());
        } else if (holder instanceof PreviewMenu preview) {
            preview.session().previewClosed(preview.viewer());
        }
    }
}
