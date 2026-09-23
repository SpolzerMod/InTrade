package me.spolzer.intrade.menu;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import net.kyori.adventure.text.Component;
import org.bukkit.Bukkit;
import org.bukkit.Material;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;
import org.bukkit.inventory.meta.SkullMeta;

public final class Icons {
    private Icons() {}

    public static ItemStack icon(Material material, Component name, List<Component> lore) {
        ItemStack item = new ItemStack(material);
        ItemMeta meta = item.getItemMeta();
        meta.displayName(name);
        if (!lore.isEmpty()) meta.lore(lore);
        item.setItemMeta(meta);
        return item;
    }

    public static ItemStack icon(Material material, Component name) {
        return icon(material, name, List.of());
    }

    public static ItemStack filler(Material material) {
        ItemStack item = new ItemStack(material);
        ItemMeta meta = item.getItemMeta();
        meta.setHideTooltip(true);
        item.setItemMeta(meta);
        return item;
    }

    public static ItemStack head(UUID owner, Component name, List<Component> lore) {
        ItemStack item = icon(Material.PLAYER_HEAD, name, lore);
        SkullMeta meta = (SkullMeta) item.getItemMeta();
        meta.setOwningPlayer(Bukkit.getOfflinePlayer(owner));
        item.setItemMeta(meta);
        return item;
    }

    public static ItemStack glowing(ItemStack item, boolean glow) {
        ItemMeta meta = item.getItemMeta();
        meta.setEnchantmentGlintOverride(glow ? true : null);
        item.setItemMeta(meta);
        return item;
    }

    public static ItemStack withTopLore(ItemStack source, List<Component> top) {
        ItemStack item = source.clone();
        ItemMeta meta = item.getItemMeta();
        List<Component> lore = new ArrayList<>(top);
        List<Component> existing = meta.lore();
        if (existing != null && !existing.isEmpty()) {
            lore.add(Component.empty());
            lore.addAll(existing);
        }
        meta.lore(lore);
        item.setItemMeta(meta);
        return item;
    }

    public static ItemStack amount(ItemStack item, int amount) {
        item.setAmount(Math.max(1, Math.min(amount, item.getMaxStackSize())));
        return item;
    }
}
