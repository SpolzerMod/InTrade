package me.spolzer.intrade.trade;

import java.util.concurrent.atomic.AtomicLong;
import org.bukkit.NamespacedKey;
import org.bukkit.entity.Player;
import org.bukkit.persistence.PersistentDataType;
import org.bukkit.plugin.Plugin;

/**
 * Version of the saved trade offer that the player file matches. It is stored in the player data, so it is always
 * written together with the inventory.
 */
public final class OfferMark {
    // Unique across restarts, so a version left in an old player file never matches a newer offer
    private static final AtomicLong VERSIONS = new AtomicLong(System.currentTimeMillis() << 10);

    private final NamespacedKey key;

    public OfferMark(Plugin plugin) {
        this.key = new NamespacedKey(plugin, "offer");
    }

    static long next() {
        return VERSIONS.incrementAndGet();
    }

    /** The version, or 0 if the player has no saved offer. */
    public long get(Player player) {
        return player.getPersistentDataContainer().getOrDefault(key, PersistentDataType.LONG, 0L);
    }

    void set(Player player, long version) {
        player.getPersistentDataContainer().set(key, PersistentDataType.LONG, version);
    }

    public void clear(Player player) {
        player.getPersistentDataContainer().remove(key);
    }
}
