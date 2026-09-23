package me.spolzer.intrade.trade;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import me.spolzer.intrade.InTradePlugin;
import me.spolzer.intrade.api.TradeOffer;
import me.spolzer.intrade.api.event.TradeCancelEvent;
import me.spolzer.intrade.api.event.TradeCompleteEvent;
import me.spolzer.intrade.config.Settings;
import me.spolzer.intrade.currency.Currencies;
import me.spolzer.intrade.currency.Currency;
import me.spolzer.intrade.storage.TradeRecord;
import me.spolzer.intrade.text.Arg;
import me.spolzer.intrade.text.Messages;
import org.bukkit.Bukkit;
import org.bukkit.entity.Player;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.ItemStack;

public final class TradeSession {
    private static final String SOUND_OPEN = "block.chest.open";
    private static final String SOUND_PUT = "entity.item_frame.add_item";
    private static final String SOUND_TAKE = "entity.item_frame.remove_item";
    private static final String SOUND_COINS = "entity.experience_orb.pickup";
    private static final String SOUND_CHANGED = "block.note_block.pling";
    private static final String SOUND_READY = "block.note_block.chime";
    private static final String SOUND_TICK = "block.note_block.hat";
    private static final String SOUND_ERROR = "block.note_block.bass";
    private static final String SOUND_CANCEL = "entity.villager.no";
    private static final String SOUND_DONE = "entity.player.levelup";

    private final InTradePlugin plugin;
    private final TradeSide a;
    private final TradeSide b;
    private long lockUntil;
    private long countdownEnd;
    private int lastCountdownSecond = -1;
    private boolean finished;

    TradeSession(InTradePlugin plugin, Player first, Player second) {
        this.plugin = plugin;
        this.a = new TradeSide(first);
        this.b = new TradeSide(second);
        a.menu = new TradeMenu(this, a, b);
        b.menu = new TradeMenu(this, b, a);
    }

    Messages messages() { return plugin.messages(); }
    Settings settings() { return plugin.settings(); }
    Currencies currencies() { return plugin.currencies(); }
    public boolean finished() { return finished; }

    public TradeSide sideOf(Player player) {
        if (a.player.getUniqueId().equals(player.getUniqueId())) return a;
        return b.player.getUniqueId().equals(player.getUniqueId()) ? b : null;
    }

    private TradeSide other(TradeSide side) { return side == a ? b : a; }

    boolean countingDown() { return countdownEnd > 0; }
    int secondsLeft(long now) { return (int) Math.max(1, (countdownEnd - now + 999) / 1000); }
    boolean locked(long now) { return now < lockUntil; }
    int lockSecondsLeft(long now) { return (int) Math.max(1, (lockUntil - now + 999) / 1000); }
    boolean bothEmpty() { return a.isEmpty() && b.isEmpty(); }

    void open() {
        render();
        for (TradeSide side : List.of(a, b)) {
            if (side.player.openInventory(side.menu.getInventory()) == null) {
                stop(TradeCancelEvent.Reason.CANCELLED, "trade.cancelled.blocked", side, null);
                return;
            }
            play(side.player, SOUND_OPEN, 1.2f);
        }
    }

    void tick() {
        if (finished) return;
        for (TradeSide side : List.of(a, b)) {
            if (!side.player.isOnline()) {
                leave(side, false);
                return;
            }
            // Closed by something that did not fire an event
            if (side.mode == TradeSide.Mode.MENU && !side.viewing(side.menu)) {
                cancel(side);
                return;
            }
        }
        long now = System.currentTimeMillis();
        if (countdownEnd > 0) {
            if (now >= countdownEnd) {
                complete();
                return;
            }
            int seconds = secondsLeft(now);
            if (seconds != lastCountdownSecond) {
                lastCountdownSecond = seconds;
                play(a.player, SOUND_TICK, 1.4f);
                play(b.player, SOUND_TICK, 1.4f);
            }
        }
        render();
    }

    private void render() {
        long now = System.currentTimeMillis();
        a.menu.render(now);
        b.menu.render(now);
    }

    void offer(TradeSide side, Inventory from, int slot, int amount) {
        ItemStack source = from.getItem(slot);
        if (Inventories.empty(source)) return;
        if (settings().isBlocked(source.getType()) && !side.player.hasPermission("intrade.bypass.blocked")) {
            messages().send(side.player, "trade.blocked-item", TradeMenu.itemArgs(source));
            error(side.player);
            return;
        }

        long now = System.currentTimeMillis();
        int max = source.getMaxStackSize();
        int wanted = Math.min(amount, source.getAmount());
        int left = wanted;
        for (int i = 0; i < TradeSide.SLOTS && left > 0; i++) {
            ItemStack item = side.items[i];
            if (item != null && item.isSimilar(source) && item.getAmount() < max) {
                int moved = Math.min(left, max - item.getAmount());
                item.setAmount(item.getAmount() + moved);
                side.changedAt[i] = now;
                left -= moved;
            }
        }
        for (int i = 0; i < TradeSide.SLOTS && left > 0; i++) {
            if (side.items[i] != null) continue;
            int moved = Math.min(left, max);
            side.items[i] = source.asQuantity(moved);
            side.removed[i] = null;
            side.changedAt[i] = now;
            left -= moved;
        }

        int moved = wanted - left;
        if (moved == 0) {
            messages().send(side.player, "trade.offer-full");
            error(side.player);
            return;
        }
        int remaining = source.getAmount() - moved;
        from.setItem(slot, remaining > 0 ? source.asQuantity(remaining) : null);
        play(side.player, SOUND_PUT, 1.0f);
        changed(side);
    }

    void take(TradeSide side, int index, int amount) {
        ItemStack item = side.items[index];
        if (item == null) return;
        int wanted = Math.min(amount, item.getAmount());
        int returned = wanted;
        for (ItemStack rest : Inventories.give(side.player, List.of(item.asQuantity(wanted)))) returned -= rest.getAmount();
        if (returned <= 0) {
            messages().send(side.player, "trade.inventory-full");
            error(side.player);
            return;
        }
        if (returned >= item.getAmount()) {
            side.removed[index] = item.clone();
            side.items[index] = null;
        } else {
            item.setAmount(item.getAmount() - returned);
            side.removed[index] = null;
        }
        side.changedAt[index] = System.currentTimeMillis();
        play(side.player, SOUND_TAKE, 1.0f);
        changed(side);
    }

    void setCurrency(TradeSide side, Currency currency, double amount) {
        if (amount == side.currency(currency.id())) return;
        if (amount > 0 && amount > currency.balance(side.player)) {
            messages().send(side.player, "trade.not-enough-own", currencyArgs(side.player, currency, amount));
            error(side.player);
            return;
        }
        if (amount > 0) side.currencies.put(currency.id(), amount);
        else side.currencies.remove(currency.id());
        side.currencyChangedAt.put(currency.id(), System.currentTimeMillis());
        play(side.player, SOUND_COINS, 1.2f);
        changed(side);
    }

    void askAmount(TradeSide side, Currency currency) {
        side.mode = TradeSide.Mode.INPUT;
        unready(side);
        Bukkit.getScheduler().runTask(plugin, () -> {
            if (finished) return;
            side.player.closeInventory();
            plugin.prompts().ask(side.player, currency, side.currency(currency.id()), value -> {
                if (finished || !side.player.isOnline()) return;
                side.mode = TradeSide.Mode.MENU;
                if (value != null) setCurrency(side, currency, value);
                reopen(side);
            });
        });
    }

    void preview(TradeSide side, ItemStack container) {
        side.mode = TradeSide.Mode.PREVIEW;
        ItemStack snapshot = container.clone();
        Bukkit.getScheduler().runTask(plugin, () -> {
            if (finished) return;
            side.preview = new PreviewMenu(this, side, snapshot,
                    messages().get(side.player, "preview.title", TradeMenu.itemArgs(snapshot)));
            if (side.player.openInventory(side.preview.getInventory()) == null) {
                side.mode = TradeSide.Mode.MENU;
                reopen(side);
            } else {
                render();
            }
        });
    }

    public void previewClosed(TradeSide side) {
        if (finished || side.mode != TradeSide.Mode.PREVIEW) return;
        Bukkit.getScheduler().runTask(plugin, () -> {
            if (finished || side.mode != TradeSide.Mode.PREVIEW) return;
            side.mode = TradeSide.Mode.MENU;
            side.preview = null;
            reopen(side);
        });
    }

    private void reopen(TradeSide side) {
        render();
        if (side.player.openInventory(side.menu.getInventory()) == null) {
            stop(TradeCancelEvent.Reason.CANCELLED, "trade.cancelled.blocked", side, null);
        }
    }

    void toggleReady(TradeSide side) {
        long now = System.currentTimeMillis();
        if (side.ready) {
            unready(side);
            play(side.player, SOUND_ERROR, 1.0f);
            return;
        }
        if (locked(now) || bothEmpty()) {
            error(side.player);
            return;
        }
        for (Map.Entry<String, Double> entry : side.currencyMap().entrySet()) {
            Currency currency = currencies().byId(entry.getKey());
            if (currency == null || currency.balance(side.player) < entry.getValue()) {
                if (currency != null) messages().send(side.player, "trade.not-enough-own", currencyArgs(side.player, currency, entry.getValue()));
                error(side.player);
                return;
            }
        }

        side.ready = true;
        play(side.player, SOUND_READY, 1.6f);
        play(other(side).player, SOUND_READY, 1.6f);
        if (other(side).ready) {
            if (settings().confirmSeconds == 0) {
                complete();
                return;
            }
            countdownEnd = now + settings().confirmSeconds * 1000L;
            lastCountdownSecond = -1;
        }
        render();
    }

    private void unready(TradeSide side) {
        side.ready = false;
        countdownEnd = 0;
        render();
    }

    // Resets both ready states and locks the button so an item cannot be swapped right before confirmation
    private void changed(TradeSide side) {
        a.ready = false;
        b.ready = false;
        countdownEnd = 0;
        lockUntil = System.currentTimeMillis() + settings().changeLockMillis;
        persist(side);
        play(other(side).player, SOUND_CHANGED, 0.7f);
        render();
    }

    public void cancel(TradeSide by) {
        stop(TradeCancelEvent.Reason.CANCELLED, "trade.cancelled.by", by, null);
    }

    public void leave(TradeSide side, boolean died) {
        if (died) stop(TradeCancelEvent.Reason.DIED, "trade.cancelled.died", side, side);
        else stop(TradeCancelEvent.Reason.LEFT, "trade.cancelled.left", side, null);
    }

    public void shutdown() {
        if (finished) return;
        finished = true;
        plugin.trades().remove(this);
        closeViews();
        returnItems(a, false);
        returnItems(b, false);
        release();
        Bukkit.getPluginManager().callEvent(new TradeCancelEvent(a.player, b.player, null, TradeCancelEvent.Reason.SHUTDOWN));
    }

    private void stop(TradeCancelEvent.Reason reason, String key, TradeSide cause, TradeSide mailSide) {
        if (finished) return;
        finished = true;
        plugin.trades().remove(this);
        Bukkit.getScheduler().runTask(plugin, this::closeViews);
        returnItems(a, a == mailSide);
        returnItems(b, b == mailSide);
        release();

        Arg who = Arg.of("player", cause.player.getName());
        for (TradeSide side : List.of(a, b)) {
            messages().send(side.player, key, who);
            play(side.player, SOUND_CANCEL, 1.0f);
        }
        Bukkit.getPluginManager().callEvent(new TradeCancelEvent(a.player, b.player, cause.player, reason));
    }

    private void complete() {
        for (TradeSide side : List.of(a, b)) {
            for (Map.Entry<String, Double> entry : side.currencyMap().entrySet()) {
                Currency currency = currencies().byId(entry.getKey());
                if (currency == null || currency.balance(side.player) < entry.getValue()) {
                    fail("trade.failed.not-enough", side);
                    return;
                }
            }
        }

        List<ItemStack> toA = b.itemList();
        List<ItemStack> toB = a.itemList();
        if (!Inventories.fits(a.player, toA)) {
            fail("trade.failed.no-space", a);
            return;
        }
        if (!Inventories.fits(b.player, toB)) {
            fail("trade.failed.no-space", b);
            return;
        }

        // Everything is withdrawn before anything is paid out. If a step fails, the steps done so far are undone
        // in reverse order and the trade stays open.
        List<Runnable> undo = new ArrayList<>();
        for (TradeSide side : List.of(a, b)) {
            for (Map.Entry<String, Double> entry : side.currencyMap().entrySet()) {
                Currency currency = currencies().byId(entry.getKey());
                double amount = entry.getValue();
                if (!currency.withdraw(side.player, amount)) {
                    rollback(undo);
                    fail("trade.failed.not-enough", side);
                    return;
                }
                undo.add(() -> currency.deposit(side.player, amount));
            }
        }
        for (TradeSide side : List.of(a, b)) {
            Player receiver = other(side).player;
            for (Map.Entry<String, Double> entry : side.currencyMap().entrySet()) {
                Currency currency = currencies().byId(entry.getKey());
                double amount = entry.getValue();
                if (!currency.deposit(receiver, amount)) {
                    rollback(undo);
                    fail("trade.failed.deposit", other(side));
                    return;
                }
                undo.add(() -> currency.withdraw(receiver, amount));
            }
        }

        Map<String, Double> aCurrencies = a.currencyMap();
        Map<String, Double> bCurrencies = b.currencyMap();
        finished = true;
        plugin.trades().remove(this);
        Arrays.fill(a.items, null);
        Arrays.fill(b.items, null);
        Bukkit.getScheduler().runTask(plugin, this::closeViews);

        deliver(a, toA);
        deliver(b, toB);
        release();

        plugin.storage().saveTrade(new TradeRecord(System.currentTimeMillis(),
                a.id(), a.player.getName(), b.id(), b.player.getName(),
                toB, toA, aCurrencies, bCurrencies), settings().historyEnabled).thenRun(plugin.stats()::refreshTop);
        plugin.stats().completed(a.id(), b.id());

        for (TradeSide side : List.of(a, b)) {
            messages().send(side.player, "trade.completed", Arg.of("player", other(side).player.getName()));
            play(side.player, SOUND_DONE, 1.2f);
        }
        Bukkit.getPluginManager().callEvent(new TradeCompleteEvent(a.player, b.player,
                new TradeOffer(toB, aCurrencies), new TradeOffer(toA, bCurrencies)));
    }

    private static void rollback(List<Runnable> undo) {
        for (int i = undo.size() - 1; i >= 0; i--) undo.get(i).run();
    }

    private void fail(String key, TradeSide cause) {
        a.ready = false;
        b.ready = false;
        countdownEnd = 0;
        Arg who = Arg.of("player", cause.player.getName());
        for (TradeSide side : List.of(a, b)) {
            messages().send(side.player, key, who);
            error(side.player);
        }
        render();
    }

    // The player file is written here, the escrow row right after on the storage thread. A file and a database
    // row cannot share a transaction, so a crash in the milliseconds between the two can leave them out of step.
    private void persist(TradeSide side) {
        side.player.saveData();
        plugin.storage().saveEscrow(side.id(), side.itemList());
    }

    private void release() {
        for (TradeSide side : List.of(a, b)) {
            if (side.player.isOnline()) side.player.saveData();
            plugin.storage().clearEscrow(side.id());
        }
    }

    private void returnItems(TradeSide side, boolean toMail) {
        List<ItemStack> items = side.itemList();
        Arrays.fill(side.items, null);
        if (items.isEmpty()) return;
        if (toMail || !side.player.isOnline()) {
            plugin.storage().addMail(side.id(), items);
            messages().send(side.player, "mail.saved");
            return;
        }
        deliver(side, items);
    }

    private void deliver(TradeSide side, List<ItemStack> items) {
        List<ItemStack> rest = Inventories.give(side.player, items);
        if (rest.isEmpty()) return;
        plugin.storage().addMail(side.id(), rest);
        messages().send(side.player, "mail.stored");
    }

    private void closeViews() {
        for (TradeSide side : List.of(a, b)) {
            Player player = side.player;
            if (!player.isOnline()) continue;
            if (side.mode == TradeSide.Mode.INPUT) plugin.prompts().dismiss(player);
            if (side.viewing(side.menu) || side.viewing(side.preview)) player.closeInventory();
        }
    }

    private Arg[] currencyArgs(Player owner, Currency currency, double amount) {
        return new Arg[] {
                messages().currency(owner, currency.id()),
                Arg.of("amount", currency.format(amount)),
                Arg.of("balance", currency.format(currency.balance(owner)))
        };
    }

    private static void error(Player player) {
        play(player, SOUND_ERROR, 0.6f);
    }

    static void play(Player player, String sound, float pitch) {
        if (player.isOnline()) player.playSound(player.getLocation(), sound, 0.6f, pitch);
    }
}
