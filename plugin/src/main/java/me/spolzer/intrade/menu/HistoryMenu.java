package me.spolzer.intrade.menu;

import java.math.BigDecimal;
import java.text.DateFormat;
import java.util.ArrayList;
import java.util.Date;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import me.spolzer.intrade.InTradePlugin;
import me.spolzer.intrade.currency.Currency;
import me.spolzer.intrade.storage.TradeRecord;
import me.spolzer.intrade.text.Arg;
import me.spolzer.intrade.text.Messages;
import net.kyori.adventure.text.Component;
import org.bukkit.Bukkit;
import org.bukkit.Material;
import org.bukkit.entity.Player;
import org.bukkit.event.inventory.InventoryClickEvent;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.InventoryHolder;
import org.bukkit.inventory.ItemStack;
import org.jetbrains.annotations.NotNull;

public final class HistoryMenu implements InventoryHolder {
    private static final int PAGE_SIZE = 45;
    private static final int PREVIOUS = 45;
    private static final int INFO = 49;
    private static final int NEXT = 53;
    private static final int SUMMARY_LINES = 4;

    private final InTradePlugin plugin;
    private final Player viewer;
    private final UUID owner;
    private final String ownerName;
    private final int page;
    private final boolean hasNext;
    private final Map<Integer, TradeRecord> entries = new HashMap<>();
    private final Inventory inventory;

    private HistoryMenu(InTradePlugin plugin, Player viewer, UUID owner, String ownerName, int page, List<TradeRecord> records) {
        this.plugin = plugin;
        this.viewer = viewer;
        this.owner = owner;
        this.ownerName = ownerName;
        this.page = page;
        this.hasNext = records.size() > PAGE_SIZE;
        Messages messages = plugin.messages();
        this.inventory = Bukkit.createInventory(this, 54,
                messages.get(viewer, "history.title", Arg.of("player", ownerName), Arg.of("page", page + 1)));

        for (int i = 0; i < records.size() && i < PAGE_SIZE; i++) {
            entries.put(i, records.get(i));
            inventory.setItem(i, entry(records.get(i)));
        }
        ItemStack filler = Icons.filler(Material.BLACK_STAINED_GLASS_PANE);
        for (int slot = 45; slot < 54; slot++) inventory.setItem(slot, filler);
        if (page > 0) inventory.setItem(PREVIOUS, Icons.icon(Material.ARROW, messages.item(viewer, "history.previous")));
        if (hasNext) inventory.setItem(NEXT, Icons.icon(Material.ARROW, messages.item(viewer, "history.next")));
        inventory.setItem(INFO, Icons.icon(Material.BOOK,
                messages.item(viewer, records.isEmpty() ? "history.empty" : "history.info"), messages.lore(viewer, "history.info-lore")));
    }

    public static void open(InTradePlugin plugin, Player viewer, UUID owner, String ownerName, int page) {
        plugin.storage().history(owner, page * PAGE_SIZE, PAGE_SIZE + 1).thenAcceptAsync(records -> {
            if (viewer.isOnline()) viewer.openInventory(new HistoryMenu(plugin, viewer, owner, ownerName, page, records).getInventory());
        }, plugin.mainThread());
    }

    private ItemStack entry(TradeRecord record) {
        Messages messages = plugin.messages();
        DateFormat format = DateFormat.getDateTimeInstance(DateFormat.MEDIUM, DateFormat.SHORT, messages.locale());
        List<Component> lore = new ArrayList<>(messages.lore(viewer, "history.entry.header", Arg.of("date", format.format(new Date(record.time())))));
        lore.addAll(messages.lore(viewer, "history.entry.gave"));
        lore.addAll(summary(record.gaveItems(owner), record.gaveCurrencies(owner)));
        lore.addAll(messages.lore(viewer, "history.entry.got"));
        lore.addAll(summary(record.gotItems(owner), record.gotCurrencies(owner)));
        lore.addAll(messages.lore(viewer, "history.entry.footer"));
        return Icons.head(record.partnerOf(owner),
                messages.item(viewer, "history.entry.name", Arg.of("player", record.partnerNameOf(owner))), lore);
    }

    private List<Component> summary(List<ItemStack> items, Map<String, BigDecimal> currencies) {
        Messages messages = plugin.messages();
        List<Component> lines = new ArrayList<>();
        currencies.forEach((id, amount) -> lines.add(messages.item(viewer, "history.entry.currency",
                messages.currency(viewer, id), Arg.of("amount", format(plugin, id, amount)))));
        int shown = 0;
        for (ItemStack item : items) {
            if (lines.size() >= SUMMARY_LINES) break;
            lines.add(messages.item(viewer, "history.entry.item", Arg.of("item", item.effectiveName()), Arg.of("count", item.getAmount())));
            shown++;
        }
        if (items.size() > shown) lines.add(messages.item(viewer, "history.entry.more", Arg.of("count", items.size() - shown)));
        if (lines.isEmpty()) lines.add(messages.item(viewer, "history.entry.nothing"));
        return lines;
    }

    // A currency may be disabled since the trade, then the raw number is shown
    static String format(InTradePlugin plugin, String id, BigDecimal amount) {
        Currency currency = plugin.currencies().byId(id);
        return currency != null ? currency.format(amount) : amount.stripTrailingZeros().toPlainString();
    }

    public void handleClick(InventoryClickEvent event) {
        event.setCancelled(true);
        if (event.getClickedInventory() != inventory) return;
        int slot = event.getSlot();
        if (slot == PREVIOUS && page > 0) {
            open(plugin, viewer, owner, ownerName, page - 1);
        } else if (slot == NEXT && hasNext) {
            open(plugin, viewer, owner, ownerName, page + 1);
        } else if (entries.containsKey(slot)) {
            TradeRecord record = entries.get(slot);
            Bukkit.getScheduler().runTask(plugin, () ->
                    viewer.openInventory(new HistoryDetailMenu(plugin, viewer, owner, ownerName, page, record).getInventory()));
        }
    }

    @Override
    public @NotNull Inventory getInventory() { return inventory; }
}
