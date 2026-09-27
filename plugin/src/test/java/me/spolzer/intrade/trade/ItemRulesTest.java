package me.spolzer.intrade.trade;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import java.util.Set;
import java.util.function.Predicate;
import org.bukkit.Material;
import org.bukkit.block.ShulkerBox;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.BlockStateMeta;
import org.bukkit.inventory.meta.BundleMeta;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockbukkit.mockbukkit.MockBukkit;
import org.mockbukkit.mockbukkit.ServerMock;
import org.mockbukkit.mockbukkit.entity.PlayerMock;

class ItemRulesTest {
    private static final Predicate<Material> BLOCKED = Set.of(Material.BEDROCK)::contains;

    private ServerMock server;

    @BeforeEach
    void start() {
        server = MockBukkit.mock();
    }

    @AfterEach
    void stop() {
        MockBukkit.unmock();
    }

    @Test
    void blockedItemItself() {
        ItemStack bedrock = new ItemStack(Material.BEDROCK);
        assertEquals(bedrock, Containers.findBlocked(bedrock, BLOCKED));
        assertNull(Containers.findBlocked(new ItemStack(Material.DIRT), BLOCKED));
    }

    @Test
    void blockedItemInsideAShulkerBox() {
        ItemStack box = shulker(new ItemStack(Material.DIRT, 5), new ItemStack(Material.BEDROCK));
        assertEquals(Material.BEDROCK, Containers.findBlocked(box, BLOCKED).getType());
        assertNull(Containers.findBlocked(shulker(new ItemStack(Material.DIRT)), BLOCKED));
    }

    @Test
    void blockedItemInsideNestedBundles() {
        ItemStack inner = bundle(new ItemStack(Material.BEDROCK));
        ItemStack outer = bundle(new ItemStack(Material.STICK), inner);
        assertEquals(Material.BEDROCK, Containers.findBlocked(outer, BLOCKED).getType());
        assertNull(Containers.findBlocked(bundle(new ItemStack(Material.STICK)), BLOCKED));
    }

    @Test
    void roomCountsFreeSlotsAndPartialStacks() {
        PlayerMock player = server.addPlayer();
        ItemStack dirt = new ItemStack(Material.DIRT);
        assertEquals(36 * 64, Inventories.room(player, dirt));

        for (int slot = 0; slot < 36; slot++) player.getInventory().setItem(slot, new ItemStack(Material.STONE, 64));
        player.getInventory().setItem(0, new ItemStack(Material.DIRT, 60));
        assertEquals(4, Inventories.room(player, dirt));
        assertEquals(0, Inventories.room(player, new ItemStack(Material.DIAMOND)));
    }

    @Test
    void fitsChecksAllIncomingItemsTogether() {
        PlayerMock player = server.addPlayer();
        for (int slot = 0; slot < 35; slot++) player.getInventory().setItem(slot, new ItemStack(Material.STONE, 64));

        assertTrue(Inventories.fits(player, List.of(new ItemStack(Material.DIRT, 64))));
        assertTrue(Inventories.fits(player, List.of(new ItemStack(Material.STONE, 30), new ItemStack(Material.STONE, 34))));
        assertFalse(Inventories.fits(player, List.of(new ItemStack(Material.DIRT, 64), new ItemStack(Material.DIRT))));
        assertFalse(Inventories.fits(player, List.of(new ItemStack(Material.DIRT), new ItemStack(Material.DIAMOND))));
    }

    private static ItemStack shulker(ItemStack... contents) {
        ItemStack box = new ItemStack(Material.SHULKER_BOX);
        BlockStateMeta meta = (BlockStateMeta) box.getItemMeta();
        ShulkerBox state = (ShulkerBox) meta.getBlockState();
        state.getInventory().addItem(contents);
        meta.setBlockState(state);
        box.setItemMeta(meta);
        return box;
    }

    private static ItemStack bundle(ItemStack... contents) {
        ItemStack bundle = new ItemStack(Material.BUNDLE);
        BundleMeta meta = (BundleMeta) bundle.getItemMeta();
        for (ItemStack item : contents) meta.addItem(item);
        bundle.setItemMeta(meta);
        return bundle;
    }
}
