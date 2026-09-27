package me.spolzer.intrade.trade;

import java.util.List;
import net.kyori.adventure.text.Component;
import org.bukkit.Bukkit;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.InventoryHolder;
import org.bukkit.inventory.ItemStack;
import org.jetbrains.annotations.NotNull;

public final class PreviewMenu implements InventoryHolder {
    private final TradeSession session;
    private final TradeSide viewer;
    private final Inventory inventory;

    PreviewMenu(TradeSession session, TradeSide viewer, ItemStack container, Component title) {
        this.session = session;
        this.viewer = viewer;
        this.inventory = Bukkit.createInventory(this, 27, title);
        List<ItemStack> contents = Containers.contents(container);
        for (int i = 0; i < contents.size() && i < 27; i++) inventory.setItem(i, contents.get(i));
    }

    @Override
    public @NotNull Inventory getInventory() { return inventory; }
    public TradeSession session() { return session; }
    public TradeSide viewer() { return viewer; }
}
