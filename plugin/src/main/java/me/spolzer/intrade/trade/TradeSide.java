package me.spolzer.intrade.trade;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;

public final class TradeSide {
    public static final int SLOTS = 16;

    public enum Mode { MENU, INPUT, PREVIEW }

    final Player player;
    final ItemStack[] items = new ItemStack[SLOTS];
    final long[] changedAt = new long[SLOTS];
    final ItemStack[] removed = new ItemStack[SLOTS];
    final Map<String, BigDecimal> currencies = new LinkedHashMap<>();
    final Map<String, Long> currencyChangedAt = new HashMap<>();
    boolean ready;
    // Offer changed since the last save of the player file and escrow
    boolean dirty;
    Mode mode = Mode.MENU;
    TradeMenu menu;
    PreviewMenu preview;

    TradeSide(Player player) {
        this.player = player;
    }

    public UUID id() { return player.getUniqueId(); }
    public Mode mode() { return mode; }
    BigDecimal currency(String id) { return currencies.getOrDefault(id, BigDecimal.ZERO); }

    List<ItemStack> itemList() {
        List<ItemStack> list = new ArrayList<>();
        for (ItemStack item : items) {
            if (item != null) list.add(item.clone());
        }
        return list;
    }

    Map<String, BigDecimal> currencyMap() {
        Map<String, BigDecimal> map = new LinkedHashMap<>();
        currencies.forEach((id, amount) -> {
            if (amount.signum() > 0) map.put(id, amount);
        });
        return map;
    }

    boolean isEmpty() {
        for (ItemStack item : items) {
            if (item != null) return false;
        }
        return currencyMap().isEmpty();
    }

    boolean recentlyChanged(int index, long now, long window) {
        return changedAt[index] != 0 && now - changedAt[index] < window;
    }

    boolean currencyRecentlyChanged(String id, long now, long window) {
        Long at = currencyChangedAt.get(id);
        return at != null && now - at < window;
    }

    boolean viewing(Object menu) {
        return menu instanceof TradeMenu trade && trade.getInventory().getViewers().contains(player)
                || menu instanceof PreviewMenu look && look.getInventory().getViewers().contains(player);
    }
}
