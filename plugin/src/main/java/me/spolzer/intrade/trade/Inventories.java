package me.spolzer.intrade.trade;

import java.util.ArrayList;
import java.util.List;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;

final class Inventories {
    private Inventories() {}

    static boolean fits(Player player, List<ItemStack> incoming) {
        ItemStack[] storage = player.getInventory().getStorageContents();
        for (int i = 0; i < storage.length; i++) {
            storage[i] = empty(storage[i]) ? null : storage[i].clone();
        }
        for (ItemStack stack : incoming) {
            int left = stack.getAmount();
            int max = stack.getMaxStackSize();
            for (int i = 0; i < storage.length && left > 0; i++) {
                ItemStack slot = storage[i];
                if (slot != null && slot.isSimilar(stack) && slot.getAmount() < max) {
                    int moved = Math.min(left, max - slot.getAmount());
                    slot.setAmount(slot.getAmount() + moved);
                    left -= moved;
                }
            }
            for (int i = 0; i < storage.length && left > 0; i++) {
                if (storage[i] == null) {
                    int moved = Math.min(left, max);
                    storage[i] = stack.asQuantity(moved);
                    left -= moved;
                }
            }
            if (left > 0) return false;
        }
        return true;
    }

    static List<ItemStack> give(Player player, List<ItemStack> items) {
        if (items.isEmpty()) return List.of();
        return new ArrayList<>(player.getInventory().addItem(items.toArray(new ItemStack[0])).values());
    }

    static boolean empty(ItemStack item) {
        return item == null || item.getType().isAir() || item.getAmount() <= 0;
    }
}
