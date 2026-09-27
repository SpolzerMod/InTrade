package me.spolzer.intrade.trade;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.function.BooleanSupplier;
import java.util.logging.Logger;
import me.spolzer.intrade.InTradePlugin;
import me.spolzer.intrade.api.TradeOffer;
import me.spolzer.intrade.api.event.TradeCancelEvent;
import me.spolzer.intrade.api.event.TradeCompleteEvent;
import me.spolzer.intrade.config.Settings;
import me.spolzer.intrade.config.TradeSound;
import me.spolzer.intrade.currency.Currencies;
import me.spolzer.intrade.currency.Currency;
import me.spolzer.intrade.menu.Icons;
import me.spolzer.intrade.storage.TradeRecord;
import me.spolzer.intrade.text.Arg;
import me.spolzer.intrade.text.Messages;
import org.bukkit.Bukkit;
import org.bukkit.entity.Player;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.ItemStack;

/**
 * One open trade between two players.
 *
 * <p>Items in the trade window are protected against a crash by the offer rows of {@code TradeStorage} and the
 * version in the player file ({@link OfferMark}). Every change is saved first and applied only after the commit, in
 * one main thread step together with the new version. Player files are only written on the main thread, so any
 * saved file, including autosaves, points to an offer that is already in the database.
 */
public final class TradeSession {
    // While an offer changes, the player file is saved at most this often. This only lets old offer rows be
    // deleted, the protection itself does not depend on it.
    private static final long SAVE_INTERVAL_MILLIS = 2000;

    private final InTradePlugin plugin;
    private final TradeSide a;
    private final TradeSide b;
    private final long startedAt = System.currentTimeMillis();
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
            sound(TradeSound.OPEN, side.player);
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
        long maxDuration = settings().maxTradeMillis();
        if (maxDuration > 0 && now - startedAt >= maxDuration) {
            stop(TradeCancelEvent.Reason.TIMEOUT, "trade.cancelled.timeout", null, null);
            return;
        }
        if (outOfRange()) {
            stop(TradeCancelEvent.Reason.DISTANCE, "trade.cancelled.distance", null, null);
            return;
        }
        for (TradeSide side : List.of(a, b)) {
            if (!side.pending && side.version != side.savedVersion && now - side.savedAt >= SAVE_INTERVAL_MILLIS) {
                saveFile(side, now);
            }
        }
        if (countdownEnd > 0) {
            if (now >= countdownEnd) {
                complete();
                return;
            }
            int seconds = secondsLeft(now);
            if (seconds != lastCountdownSecond) {
                lastCountdownSecond = seconds;
                sound(TradeSound.COUNTDOWN, a.player);
                sound(TradeSound.COUNTDOWN, b.player);
            }
        }
        render();
    }

    // The same limits as for a request, checked again because players can be teleported during a trade
    private boolean outOfRange() {
        Settings settings = settings();
        Player first = a.player;
        Player second = b.player;
        if (settings.isWorldDisabled(first.getWorld().getName()) || settings.isWorldDisabled(second.getWorld().getName())) return true;
        if (first.hasPermission("intrade.bypass.distance") || second.hasPermission("intrade.bypass.distance")) return false;
        boolean sameWorld = first.getWorld().equals(second.getWorld());
        if (settings.sameWorld() && !sameWorld) return true;
        double max = settings.maxDistance();
        return max > 0 && (!sameWorld || first.getLocation().distanceSquared(second.getLocation()) > max * max);
    }

    private void render() {
        long now = System.currentTimeMillis();
        a.menu.render(now);
        b.menu.render(now);
    }

    void offer(TradeSide side, Inventory from, int slot, int amount) {
        if (side.pending) return;
        ItemStack source = from.getItem(slot);
        if (Inventories.empty(source)) return;
        if (!side.player.hasPermission("intrade.bypass.blocked")) {
            ItemStack blocked = Containers.findBlocked(source, settings()::isBlocked);
            if (blocked != null) {
                if (blocked == source) {
                    messages().send(side.player, "trade.blocked-item", TradeMenu.itemArgs(source));
                } else {
                    messages().send(side.player, "trade.blocked-content",
                            Arg.of("container", Icons.name(source)), Arg.of("item", Icons.name(blocked)));
                }
                error(side.player);
                return;
            }
        }

        ItemStack[] next = side.copyItems();
        int max = source.getMaxStackSize();
        int wanted = Math.min(amount, source.getAmount());
        int left = wanted;
        for (int i = 0; i < TradeSide.SLOTS && left > 0; i++) {
            ItemStack item = next[i];
            if (item != null && item.isSimilar(source) && item.getAmount() < max) {
                int moved = Math.min(left, max - item.getAmount());
                item.setAmount(item.getAmount() + moved);
                left -= moved;
            }
        }
        for (int i = 0; i < TradeSide.SLOTS && left > 0; i++) {
            if (next[i] != null) continue;
            int moved = Math.min(left, max);
            next[i] = source.asQuantity(moved);
            left -= moved;
        }

        int moved = wanted - left;
        if (moved == 0) {
            messages().send(side.player, "trade.offer-full");
            error(side.player);
            return;
        }
        ItemStack taken = source.clone();
        int remaining = source.getAmount() - moved;
        commit(side, next, TradeSound.PUT, () -> {
            // The slot may have changed while the offer was being saved
            if (!taken.equals(from.getItem(slot))) return false;
            from.setItem(slot, remaining > 0 ? taken.asQuantity(remaining) : null);
            return true;
        });
    }

    void take(TradeSide side, int index, int amount) {
        if (side.pending) return;
        ItemStack item = side.items[index];
        if (item == null) return;
        int returned = Math.min(Math.min(amount, item.getAmount()), Inventories.room(side.player, item));
        if (returned <= 0) {
            messages().send(side.player, "trade.inventory-full");
            error(side.player);
            return;
        }
        ItemStack[] next = side.copyItems();
        if (returned >= item.getAmount()) next[index] = null;
        else next[index].setAmount(item.getAmount() - returned);
        ItemStack back = item.asQuantity(returned);
        commit(side, next, TradeSound.TAKE, () -> {
            if (Inventories.room(side.player, back) < returned) return false;
            side.player.getInventory().addItem(back);
            return true;
        });
    }

    // Saves the new offer, then moves the items and stores the new version in one main thread step
    private void commit(TradeSide side, ItemStack[] next, TradeSound sound, BooleanSupplier moveItems) {
        side.pending = true;
        a.ready = false;
        b.ready = false;
        countdownEnd = 0;
        long version = OfferMark.next();
        plugin.storage().saveOffer(side.id(), version, TradeSide.list(next)).whenCompleteAsync((ignored, failure) -> {
            side.pending = false;
            if (finished) return;
            if (failure != null) {
                messages().send(side.player, "trade.save-failed");
                error(side.player);
                render();
                return;
            }
            if (!side.player.isOnline() || !moveItems.getAsBoolean()) {
                render();
                return;
            }
            long now = System.currentTimeMillis();
            for (int i = 0; i < TradeSide.SLOTS; i++) {
                ItemStack before = side.items[i];
                if (Objects.equals(before, next[i])) continue;
                side.removed[i] = next[i] == null ? before.clone() : null;
                side.changedAt[i] = now;
                side.items[i] = next[i];
            }
            side.version = version;
            plugin.offerMark().set(side.player, version);
            sound(sound, side.player);
            changed(side);
        }, plugin.mainThread());
    }

    // Once the file holds the current version, the older rows are not needed after a crash
    private void saveFile(TradeSide side, long now) {
        side.player.saveData();
        side.savedVersion = side.version;
        side.savedAt = now;
        plugin.storage().dropOffersExcept(side.id(), side.version);
    }

    void setCurrency(TradeSide side, Currency currency, BigDecimal amount) {
        if (amount.compareTo(side.currency(currency.id())) == 0) return;
        if (amount.signum() > 0 && amount.compareTo(currency.balance(side.player)) > 0) {
            messages().send(side.player, "trade.not-enough-own", currencyArgs(side.player, currency, amount));
            error(side.player);
            return;
        }
        if (amount.signum() > 0) side.currencies.put(currency.id(), amount);
        else side.currencies.remove(currency.id());
        side.currencyChangedAt.put(currency.id(), System.currentTimeMillis());
        sound(TradeSound.CURRENCY, side.player);
        changed(side);
    }

    /** Removes the amounts of currencies that were disabled by a reload, so the trade can still complete. */
    void dropDisabledCurrencies() {
        if (finished) return;
        for (TradeSide side : List.of(a, b)) {
            if (!side.currencies.keySet().removeIf(id -> currencies().byId(id) == null)) continue;
            messages().send(side.player, "trade.currency-disabled");
            messages().send(other(side).player, "trade.currency-disabled");
            changed(side);
        }
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
                if (value != null && currencies().byId(currency.id()) != null) setCurrency(side, currency, value);
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
            sound(TradeSound.UNREADY, side.player);
            return;
        }
        if (side.pending || locked(now) || bothEmpty()) {
            error(side.player);
            return;
        }
        for (Map.Entry<String, BigDecimal> entry : side.currencyMap().entrySet()) {
            Currency currency = currencies().byId(entry.getKey());
            if (currency == null || currency.balance(side.player).compareTo(entry.getValue()) < 0) {
                if (currency != null) messages().send(side.player, "trade.not-enough-own", currencyArgs(side.player, currency, entry.getValue()));
                error(side.player);
                return;
            }
        }

        side.ready = true;
        sound(TradeSound.READY, side.player);
        sound(TradeSound.READY, other(side).player);
        if (other(side).ready) {
            if (settings().confirmSeconds() == 0) {
                complete();
                return;
            }
            countdownEnd = now + settings().confirmSeconds() * 1000L;
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
        lockUntil = System.currentTimeMillis() + settings().changeLockMillis();
        sound(TradeSound.CHANGED, other(side).player);
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
        Bukkit.getPluginManager().callEvent(new TradeCancelEvent(a.player, b.player, null, TradeCancelEvent.Reason.SHUTDOWN));
    }

    private void stop(TradeCancelEvent.Reason reason, String key, TradeSide cause, TradeSide died) {
        if (finished) return;
        finished = true;
        plugin.trades().remove(this);
        Bukkit.getScheduler().runTask(plugin, this::closeViews);
        returnItems(a, a == died);
        returnItems(b, b == died);

        Arg who = Arg.of("player", cause != null ? cause.player.getName() : "");
        for (TradeSide side : List.of(a, b)) {
            messages().send(side.player, key, who);
            sound(TradeSound.CANCELLED, side.player);
        }
        Bukkit.getPluginManager().callEvent(new TradeCancelEvent(a.player, b.player, cause != null ? cause.player : null, reason));
    }

    private void complete() {
        for (TradeSide side : List.of(a, b)) {
            for (Map.Entry<String, BigDecimal> entry : side.currencyMap().entrySet()) {
                Currency currency = currencies().byId(entry.getKey());
                if (currency == null || currency.balance(side.player).compareTo(entry.getValue()) < 0) {
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
        List<Transfer> undo = new ArrayList<>();
        for (TradeSide side : List.of(a, b)) {
            for (Map.Entry<String, BigDecimal> entry : side.currencyMap().entrySet()) {
                Currency currency = currencies().byId(entry.getKey());
                BigDecimal amount = entry.getValue();
                if (!currency.withdraw(side.player, amount)) {
                    rollback(undo);
                    fail("trade.failed.not-enough", side);
                    return;
                }
                undo.add(new Transfer(side.player, currency, amount, true));
            }
        }
        for (TradeSide side : List.of(a, b)) {
            Player receiver = other(side).player;
            for (Map.Entry<String, BigDecimal> entry : side.currencyMap().entrySet()) {
                Currency currency = currencies().byId(entry.getKey());
                BigDecimal amount = entry.getValue();
                if (!currency.deposit(receiver, amount)) {
                    rollback(undo);
                    fail("trade.failed.deposit", other(side));
                    return;
                }
                undo.add(new Transfer(receiver, currency, amount, false));
            }
        }

        Map<String, BigDecimal> aCurrencies = a.currencyMap();
        Map<String, BigDecimal> bCurrencies = b.currencyMap();
        finished = true;
        plugin.trades().remove(this);
        Arrays.fill(a.items, null);
        Arrays.fill(b.items, null);
        Bukkit.getScheduler().runTask(plugin, this::closeViews);

        // A side that offered no items has no saved offer yet. Its new version goes into the player file right away:
        // until the commit it points to no row, which is correct, as nothing of that player is in the trade.
        for (TradeSide side : List.of(a, b)) {
            if (side.version != 0) continue;
            side.version = OfferMark.next();
            plugin.offerMark().set(side.player, side.version);
        }
        // After this commit each offer row holds what its owner receives, so the items are handed out only then
        plugin.storage().swapOffers(a.id(), a.version, toA, b.id(), b.version, toB).whenCompleteAsync((ignored, failure) -> {
            receive(a, toA, failure == null);
            receive(b, toB, failure == null);
            plugin.storage().saveTrade(new TradeRecord(System.currentTimeMillis(),
                    a.id(), a.player.getName(), b.id(), b.player.getName(),
                    toB, toA, aCurrencies, bCurrencies), settings().historyEnabled(), settings().pairCooldownMillis())
                    .thenAcceptAsync(counted -> {
                        if (counted) plugin.stats().traded(a.id(), b.id());
                    }, plugin.mainThread());

            for (TradeSide side : List.of(a, b)) {
                messages().send(side.player, "trade.completed", Arg.of("player", other(side).player.getName()));
                sound(TradeSound.COMPLETED, side.player);
            }
            Bukkit.getPluginManager().callEvent(new TradeCompleteEvent(a.player, b.player,
                    new TradeOffer(toB, aCurrencies), new TradeOffer(toA, bCurrencies)));
        }, plugin.mainThread());
    }

    private void receive(TradeSide side, List<ItemStack> items, boolean saved) {
        Player player = side.player;
        if (!player.isOnline()) {
            // The player file keeps the version of the row, the items are returned on the next join
            if (!saved) plugin.storage().addMail(side.id(), items);
            return;
        }
        if (!Inventories.fits(player, items)) {
            // Space was taken after the check. Everything goes to mail and what fits is delivered right away.
            if (saved) toMail(side, true);
            else plugin.storage().addMail(side.id(), items);
            return;
        }
        if (!items.isEmpty()) player.getInventory().addItem(items.toArray(new ItemStack[0]));
        release(side);
    }

    private void rollback(List<Transfer> undo) {
        for (int i = undo.size() - 1; i >= 0; i--) undo.get(i).undo(plugin.getLogger());
    }

    private record Transfer(Player player, Currency currency, BigDecimal amount, boolean withdrawn) {
        void undo(Logger logger) {
            boolean done = withdrawn ? currency.deposit(player, amount) : currency.withdraw(player, amount);
            if (!done) {
                logger.severe("Could not undo a transfer of " + amount.toPlainString() + " " + currency.id()
                        + (withdrawn ? " from " : " to ") + player.getName()
                        + " after a failed trade. The balance needs to be corrected manually");
            }
        }
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

    // Online, the items go back to the inventory in the same step as the version is removed from the player file.
    // Otherwise the saved offer is moved to trade mail as a whole.
    private void returnItems(TradeSide side, boolean died) {
        List<ItemStack> items = side.itemList();
        Arrays.fill(side.items, null);
        if (items.isEmpty()) {
            release(side);
            return;
        }
        Player player = side.player;
        if (died || !player.isOnline() || !Inventories.fits(player, items)) {
            toMail(side, !died);
            if (died) messages().send(player, "mail.saved");
            return;
        }
        player.getInventory().addItem(items.toArray(new ItemStack[0]));
        release(side);
    }

    private void toMail(TradeSide side, boolean deliverNow) {
        plugin.storage().offerToMail(side.id(), side.version).thenRunAsync(() -> {
            if (deliverNow && side.player.isOnline()) plugin.deliverMail(side.player, false);
        }, plugin.mainThread());
    }

    // The rows can be deleted only after the file without a version is saved
    private void release(TradeSide side) {
        if (side.version == 0) return;
        plugin.offerMark().clear(side.player);
        if (side.player.isOnline()) side.player.saveData();
        plugin.storage().clearOffers(side.id());
    }

    private void closeViews() {
        for (TradeSide side : List.of(a, b)) {
            Player player = side.player;
            if (!player.isOnline()) continue;
            if (side.mode == TradeSide.Mode.INPUT) plugin.prompts().dismiss(player);
            if (side.viewing(side.menu) || side.viewing(side.preview)) player.closeInventory();
        }
    }

    private Arg[] currencyArgs(Player owner, Currency currency, BigDecimal amount) {
        return new Arg[] {
                messages().currency(owner, currency.id()),
                Arg.of("amount", currency.format(amount)),
                Arg.of("balance", currency.format(currency.balance(owner)))
        };
    }

    private void error(Player player) {
        sound(TradeSound.ERROR, player);
    }

    private void sound(TradeSound sound, Player player) {
        settings().sound(sound).play(player);
    }
}
