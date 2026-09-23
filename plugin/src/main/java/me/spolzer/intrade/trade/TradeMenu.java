package me.spolzer.intrade.trade;

import java.util.ArrayList;
import java.util.List;
import me.spolzer.intrade.currency.Currency;
import me.spolzer.intrade.menu.Icons;
import me.spolzer.intrade.text.Arg;
import me.spolzer.intrade.text.Messages;
import net.kyori.adventure.text.Component;
import org.bukkit.Bukkit;
import org.bukkit.Material;
import org.bukkit.entity.Player;
import org.bukkit.event.inventory.ClickType;
import org.bukkit.event.inventory.InventoryClickEvent;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.InventoryHolder;
import org.bukkit.inventory.ItemStack;
import org.jetbrains.annotations.NotNull;

public final class TradeMenu implements InventoryHolder {
    private static final int[] DIVIDER = {4, 13, 22, 31, 40};
    private static final int[] OWN_CURRENCY = {36, 37, 38};
    private static final int[] PARTNER_CURRENCY = {44, 43, 42};
    private static final int OWN_STATUS = 39;
    private static final int PARTNER_STATUS = 41;
    private static final int OWN_HEAD = 45;
    private static final int CANCEL = 47;
    private static final int READY = 49;
    private static final int HELP = 51;
    private static final int PARTNER_HEAD = 53;

    private final TradeSession session;
    private final TradeSide side;
    private final TradeSide partner;
    private final Player viewer;
    private final Messages messages;
    private final Inventory inventory;

    TradeMenu(TradeSession session, TradeSide side, TradeSide partner) {
        this.session = session;
        this.side = side;
        this.partner = partner;
        this.viewer = side.player;
        this.messages = session.messages();
        this.inventory = Bukkit.createInventory(this, 54,
                messages.get(viewer, "menu.title", Arg.of("partner", partner.player.getName())));
        renderStatic();
    }

    @Override
    public @NotNull Inventory getInventory() { return inventory; }
    public TradeSession session() { return session; }
    public TradeSide side() { return side; }

    private static int ownSlot(int index) { return index / 4 * 9 + index % 4; }
    private static int partnerSlot(int index) { return index / 4 * 9 + 5 + index % 4; }

    private static int ownIndex(int slot) {
        int row = slot / 9, column = slot % 9;
        return row < 4 && column < 4 ? row * 4 + column : -1;
    }

    private static int partnerIndex(int slot) {
        int row = slot / 9, column = slot % 9;
        return row < 4 && column > 4 ? row * 4 + column - 5 : -1;
    }

    private void renderStatic() {
        ItemStack filler = Icons.filler(Material.BLACK_STAINED_GLASS_PANE);
        for (int slot = 45; slot < 54; slot++) inventory.setItem(slot, filler);
        inventory.setItem(OWN_HEAD, Icons.head(side.id(),
                messages.item(viewer, "menu.head.own", name(side)), messages.lore(viewer, "menu.head.own-lore")));
        inventory.setItem(PARTNER_HEAD, Icons.head(partner.id(),
                messages.item(viewer, "menu.head.partner", name(partner)), messages.lore(viewer, "menu.head.partner-lore")));
        inventory.setItem(CANCEL, Icons.icon(Material.BARRIER,
                messages.item(viewer, "menu.cancel.name"), messages.lore(viewer, "menu.cancel.lore")));
        inventory.setItem(HELP, Icons.icon(Material.KNOWLEDGE_BOOK,
                messages.item(viewer, "menu.help.name"), messages.lore(viewer, "menu.help.lore")));
    }

    void render(long now) {
        long highlight = session.settings().highlightMillis;
        for (int i = 0; i < TradeSide.SLOTS; i++) {
            inventory.setItem(ownSlot(i), side.items[i]);
            inventory.setItem(partnerSlot(i), partnerItem(i, now, highlight));
        }

        ItemStack divider = Icons.filler(dividerMaterial(now));
        for (int slot : DIVIDER) inventory.setItem(slot, divider);

        List<Currency> currencies = session.currencies().active();
        for (int i = 0; i < OWN_CURRENCY.length; i++) {
            if (i < currencies.size()) {
                inventory.setItem(OWN_CURRENCY[i], ownCurrency(currencies.get(i)));
                inventory.setItem(PARTNER_CURRENCY[i], partnerCurrency(currencies.get(i), now, highlight));
            } else {
                ItemStack empty = Icons.filler(Material.GRAY_STAINED_GLASS_PANE);
                inventory.setItem(OWN_CURRENCY[i], empty);
                inventory.setItem(PARTNER_CURRENCY[i], empty);
            }
        }

        inventory.setItem(OWN_STATUS, status(side));
        inventory.setItem(PARTNER_STATUS, status(partner));
        inventory.setItem(READY, readyButton(now));
    }

    private ItemStack partnerItem(int index, long now, long highlight) {
        ItemStack item = partner.items[index];
        boolean fresh = partner.recentlyChanged(index, now, highlight);
        if (item != null) {
            if (!fresh) return item;
            return Icons.glowing(Icons.withTopLore(item, messages.lore(viewer, "menu.changed")), true);
        }
        ItemStack gone = partner.removed[index];
        if (gone == null || !fresh) return null;
        return Icons.icon(Material.RED_STAINED_GLASS_PANE,
                messages.item(viewer, "menu.removed.name", itemArgs(gone)), messages.lore(viewer, "menu.removed.lore"));
    }

    private Material dividerMaterial(long now) {
        if (session.countingDown()) return Material.LIME_STAINED_GLASS_PANE;
        if (session.locked(now)) return Material.ORANGE_STAINED_GLASS_PANE;
        if (side.ready || partner.ready) return Material.YELLOW_STAINED_GLASS_PANE;
        return Material.WHITE_STAINED_GLASS_PANE;
    }

    private ItemStack ownCurrency(Currency currency) {
        Arg name = messages.currency(viewer, currency.id());
        if (!session.currencies().allowed(viewer, currency)) {
            return Icons.icon(Material.GRAY_STAINED_GLASS_PANE,
                    messages.item(viewer, "menu.currency.locked.name", name), messages.lore(viewer, "menu.currency.locked.lore"));
        }
        double amount = side.currency(currency.id());
        Arg value = Arg.of("amount", currency.format(amount));
        ItemStack icon = Icons.icon(currency.icon(),
                messages.item(viewer, amount > 0 ? "menu.currency.own.name" : "menu.currency.own.empty", name, value),
                messages.lore(viewer, "menu.currency.own.lore", name, value,
                        Arg.of("balance", currency.format(currency.balance(viewer)))));
        return Icons.glowing(icon, amount > 0);
    }

    private ItemStack partnerCurrency(Currency currency, long now, long highlight) {
        double amount = partner.currency(currency.id());
        Arg name = messages.currency(viewer, currency.id());
        Arg value = Arg.of("amount", currency.format(amount));
        boolean fresh = partner.currencyRecentlyChanged(currency.id(), now, highlight);
        List<Component> lore = new ArrayList<>();
        if (fresh) lore.addAll(messages.lore(viewer, "menu.currency.changed"));
        lore.addAll(messages.lore(viewer, "menu.currency.partner.lore", name, value));
        ItemStack icon = Icons.icon(currency.icon(),
                messages.item(viewer, amount > 0 ? "menu.currency.partner.name" : "menu.currency.partner.empty", name, value), lore);
        return Icons.glowing(icon, fresh || amount > 0);
    }

    private ItemStack status(TradeSide who) {
        Arg player = name(who);
        switch (who.mode) {
            case INPUT: return Icons.icon(Material.WRITABLE_BOOK, messages.item(viewer, "menu.status.input", player));
            case PREVIEW: return Icons.icon(Material.SPYGLASS, messages.item(viewer, "menu.status.preview", player));
            default:
                return who.ready
                        ? Icons.icon(Material.LIME_DYE, messages.item(viewer, "menu.status.ready", player))
                        : Icons.icon(Material.RED_DYE, messages.item(viewer, "menu.status.not-ready", player));
        }
    }

    private ItemStack readyButton(long now) {
        if (session.countingDown()) {
            int seconds = session.secondsLeft(now);
            ItemStack button = Icons.icon(Material.EMERALD_BLOCK,
                    messages.item(viewer, "menu.ready.countdown.name", Arg.of("seconds", seconds)),
                    messages.lore(viewer, "menu.ready.countdown.lore"));
            return Icons.amount(Icons.glowing(button, true), seconds);
        }
        if (session.locked(now)) {
            int seconds = session.lockSecondsLeft(now);
            return Icons.amount(Icons.icon(Material.CLOCK,
                    messages.item(viewer, "menu.ready.locked.name", Arg.of("seconds", seconds)),
                    messages.lore(viewer, "menu.ready.locked.lore")), seconds);
        }
        if (session.bothEmpty()) {
            return Icons.icon(Material.GRAY_CONCRETE,
                    messages.item(viewer, "menu.ready.empty.name"), messages.lore(viewer, "menu.ready.empty.lore"));
        }
        if (side.ready) {
            return Icons.icon(Material.YELLOW_CONCRETE,
                    messages.item(viewer, "menu.ready.waiting.name", name(partner)), messages.lore(viewer, "menu.ready.waiting.lore"));
        }
        return Icons.icon(Material.LIME_CONCRETE,
                messages.item(viewer, "menu.ready.idle.name"), messages.lore(viewer, "menu.ready.idle.lore"));
    }

    public void handleClick(InventoryClickEvent event) {
        event.setCancelled(true);
        if (session.finished() || side.mode != TradeSide.Mode.MENU) return;
        ClickType click = event.getClick();
        boolean left = click == ClickType.LEFT || click == ClickType.SHIFT_LEFT;
        boolean right = click == ClickType.RIGHT || click == ClickType.SHIFT_RIGHT;
        Inventory clicked = event.getClickedInventory();
        if (!left && !right || clicked == null) return;

        if (clicked != inventory) {
            ItemStack stack = clicked.getItem(event.getSlot());
            if (!Inventories.empty(stack)) session.offer(side, clicked, event.getSlot(), left ? stack.getAmount() : 1);
            return;
        }

        int slot = event.getSlot();
        int own = ownIndex(slot);
        if (own >= 0) {
            ItemStack item = side.items[own];
            if (item != null) session.take(side, own, left ? item.getAmount() : 1);
            return;
        }
        int theirs = partnerIndex(slot);
        if (theirs >= 0) {
            ItemStack item = partner.items[theirs];
            if (item != null && right && PreviewMenu.hasContents(item)) session.preview(side, item);
            return;
        }

        List<Currency> currencies = session.currencies().active();
        for (int i = 0; i < OWN_CURRENCY.length && i < currencies.size(); i++) {
            if (slot != OWN_CURRENCY[i]) continue;
            Currency currency = currencies.get(i);
            if (!session.currencies().allowed(viewer, currency)) return;
            if (left) session.askAmount(side, currency);
            else session.setCurrency(side, currency, 0);
            return;
        }

        if (slot == READY) session.toggleReady(side);
        else if (slot == CANCEL) session.cancel(side);
    }

    private static Arg name(TradeSide who) {
        return Arg.of("player", who.player.getName());
    }

    static Arg[] itemArgs(ItemStack item) {
        return new Arg[] {Arg.of("item", item.effectiveName()), Arg.of("count", item.getAmount())};
    }
}
