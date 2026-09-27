package me.spolzer.intrade.config;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.Objects;
import java.util.logging.Logger;
import org.bukkit.configuration.file.YamlConfiguration;
import org.junit.jupiter.api.Test;

class SettingsTest {
    private static final Logger LOGGER = Logger.getLogger("InTradeTest");

    private static YamlConfiguration bundled() {
        return YamlConfiguration.loadConfiguration(new InputStreamReader(
                Objects.requireNonNull(SettingsTest.class.getResourceAsStream("/config.yml")), StandardCharsets.UTF_8));
    }

    @Test
    void bundledConfigMatchesTheDefaults() {
        Settings fromFile = Settings.load(bundled(), LOGGER);
        Settings empty = Settings.load(new YamlConfiguration(), LOGGER);

        assertEquals(empty.maxTradeMillis(), fromFile.maxTradeMillis());
        assertEquals(300_000L, fromFile.maxTradeMillis());
        assertEquals(empty.pairCooldownMillis(), fromFile.pairCooldownMillis());
        assertEquals(3_600_000L, fromFile.pairCooldownMillis());
        assertEquals(empty.sounds(), fromFile.sounds());
        assertFalse(fromFile.database().mysql());
        assertEquals("main", fromFile.database().serverId());
    }

    @Test
    void negativeValuesAreClamped() {
        YamlConfiguration config = new YamlConfiguration();
        config.set("safety.max-trade-seconds", -1);
        config.set("stats.pair-cooldown-minutes", -5);
        config.set("requests.expire-seconds", 1);
        Settings settings = Settings.load(config, LOGGER);

        assertEquals(0, settings.maxTradeMillis());
        assertEquals(0, settings.pairCooldownMillis());
        assertEquals(5_000, settings.requestExpireMillis());
    }

    @Test
    void mariadbIsAnAliasForMysql() {
        YamlConfiguration config = new YamlConfiguration();
        config.set("storage.type", "MariaDB");
        assertTrue(Settings.load(config, LOGGER).database().mysql());
    }

    @Test
    void soundsAreParsed() {
        SoundEffect bell = SoundEffect.parse("block.note_block.bell 1.3", 0.6f);
        assertEquals(new SoundEffect("block.note_block.bell", 0.6f, 1.3f), bell);
        assertEquals(2.0f, SoundEffect.parse("entity.villager.no 9", 1).pitch());
        assertEquals(1.0f, SoundEffect.parse("entity.villager.no", 1).pitch());
        assertSame(SoundEffect.NONE, SoundEffect.parse("  ", 1));
        assertNull(SoundEffect.parse("entity.villager.no loud", 1));
    }
}
