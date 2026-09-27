package me.spolzer.intrade.trade;

import java.util.Arrays;
import java.util.List;
import java.util.function.Predicate;
import org.bukkit.Material;
import org.bukkit.block.ShulkerBox;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.BlockStateMeta;
import org.bukkit.inventory.meta.BundleMeta;

/** Items that hold other items: shulker boxes and bundles. */
final class Containers {
    // Bundles can be nested. The limit only guards against hand-made items with absurd nesting.
    private static final int MAX_DEPTH = 16;

    private Containers() {}

    static boolean hasContents(ItemStack item) {
        return item.getItemMeta() instanceof BundleMeta
                || item.getItemMeta() instanceof BlockStateMeta meta && meta.getBlockState() instanceof ShulkerBox;
    }

    static List<ItemStack> contents(ItemStack item) {
        if (item.getItemMeta() instanceof BundleMeta bundle) return bundle.getItems();
        if (item.getItemMeta() instanceof BlockStateMeta meta && meta.getBlockState() instanceof ShulkerBox box) {
            return Arrays.asList(box.getInventory().getContents());
        }
        return List.of();
    }

    /** The item itself or the first item inside it, at any depth, whose type is blocked, or null. */
    static ItemStack findBlocked(ItemStack item, Predicate<Material> blocked) {
        return findBlocked(item, blocked, 0);
    }

    private static ItemStack findBlocked(ItemStack item, Predicate<Material> blocked, int depth) {
        if (Inventories.empty(item)) return null;
        if (blocked.test(item.getType()) || depth > MAX_DEPTH) return item;
        for (ItemStack inner : contents(item)) {
            ItemStack found = findBlocked(inner, blocked, depth + 1);
            if (found != null) return found;
        }
        return null;
    }
}
