package me.spolzer.intrade.menu;

import java.math.BigDecimal;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import me.spolzer.intrade.InTradePlugin;
import me.spolzer.intrade.currency.Currency;
import me.spolzer.intrade.storage.TradeRecord;
import me.spolzer.intrade.text.Arg;
import me.spolzer.intrade.text.Messages;
import org.bukkit.Bukkit;
import org.bukkit.Material;
import org.bukkit.entity.Player;
import org.bukkit.event.inventory.InventoryClickEvent;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.InventoryHolder;
import org.bukkit.inventory.ItemStack;
import org.jetbrains.annotations.NotNull;

public final class HistoryDetailMenu implements InventoryHolder {
    private static final int BACK = 49;

    private final InTradePlugin plugin;
    private final Player viewer;
    private final UUID owner;
    private final String ownerName;
    private final int page;
    private final Inventory inventory;

    HistoryDetailMenu(InTradePlugin plugin, Player viewer, UUID owner, String ownerName, int page, TradeRecord record) {
        this.plugin = plugin;
        this.viewer = viewer;
        this.owner = owner;
        this.ownerName = ownerName;
        this.page = page;
        Messages messages = plugin.messages();
        Arg partner = Arg.of("player", record.partnerNameOf(owner));
        this.inventory = Bukkit.createInventory(this, 54, messages.get(viewer, "history.detail.title", partner));

        place(record.gaveItems(owner), 0);
        place(record.gotItems(owner), 5);
        ItemStack divider = Icons.filler(Material.WHITE_STAINED_GLASS_PANE);
        for (int row = 0; row < 5; row++) inventory.setItem(row * 9 + 4, divider);
        ItemStack gray = Icons.filler(Material.GRAY_STAINED_GLASS_PANE);
        for (int slot = 36; slot < 45; slot++) {
            if (slot != 40) inventory.setItem(slot, gray);
        }
        currencies(record.gaveCurrencies(owner), new int[] {36, 37, 38, 39});
        currencies(record.gotCurrencies(owner), new int[] {44, 43, 42, 41});

        ItemStack filler = Icons.filler(Material.BLACK_STAINED_GLASS_PANE);
        for (int slot = 45; slot < 54; slot++) inventory.setItem(slot, filler);
        inventory.setItem(45, Icons.head(owner, messages.item(viewer, "history.detail.gave", Arg.of("player", ownerName)), List.of()));
        inventory.setItem(53, Icons.head(record.partnerOf(owner), messages.item(viewer, "history.detail.got", partner), List.of()));
        inventory.setItem(BACK, Icons.icon(Material.ARROW, messages.item(viewer, "history.back")));
    }

    private void place(List<ItemStack> items, int column) {
        for (int i = 0; i < items.size() && i < 16; i++) inventory.setItem(i / 4 * 9 + column + i % 4, items.get(i));
    }

    private void currencies(Map<String, BigDecimal> amounts, int[] slots) {
        Messages messages = plugin.messages();
        int i = 0;
        for (Map.Entry<String, BigDecimal> entry : amounts.entrySet()) {
            if (i >= slots.length) break;
            Currency currency = plugin.currencies().byId(entry.getKey());
            inventory.setItem(slots[i++], Icons.icon(currency != null ? currency.icon() : Material.PAPER,
                    messages.item(viewer, "history.detail.currency", messages.currency(viewer, entry.getKey()),
                            Arg.of("amount", HistoryMenu.format(plugin, entry.getKey(), entry.getValue())))));
        }
    }

    public void handleClick(InventoryClickEvent event) {
        event.setCancelled(true);
        if (event.getClickedInventory() == inventory && event.getSlot() == BACK) HistoryMenu.open(plugin, viewer, owner, ownerName, page);
    }

    @Override
    public @NotNull Inventory getInventory() { return inventory; }
}
