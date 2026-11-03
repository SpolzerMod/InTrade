package me.spolzer.intrade.config;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.InputStreamReader;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.util.Objects;
import java.util.logging.Logger;
import org.bukkit.configuration.file.YamlConfiguration;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

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
    void onlyBuiltInCurrenciesAreEnabledByDefault() {
        Settings settings = Settings.load(bundled(), LOGGER);
        assertTrue(settings.currencyEnabled("money"));
        assertTrue(settings.currencyEnabled("experience"));
        assertFalse(settings.currencyEnabled("tokens"));
        assertEquals(BigDecimal.ZERO, settings.taxPercent("money"));
        assertEquals(BigDecimal.ZERO, settings.taxPercent("tokens"));

        YamlConfiguration config = new YamlConfiguration();
        config.set("currencies.tokens.enabled", true);
        config.set("currencies.experience.enabled", false);
        settings = Settings.load(config, LOGGER);
        assertTrue(settings.currencyEnabled("tokens"));
        assertFalse(settings.currencyEnabled("experience"));
    }

    @Test
    void taxPercentIsParsedAndClamped() {
        YamlConfiguration config = new YamlConfiguration();
        config.set("currencies.money.tax-percent", 2.5);
        config.set("currencies.tokens.tax-percent", "10");
        config.set("currencies.experience.tax-percent", 150);
        config.set("currencies.playerpoints.tax-percent", "much");
        config.set("currencies.tickets.tax-percent", "12.125");
        Settings settings = Settings.load(config, LOGGER);

        assertEquals(new BigDecimal("2.5"), settings.taxPercent("money"));
        assertEquals(0, new BigDecimal("10").compareTo(settings.taxPercent("tokens")));
        assertEquals(0, new BigDecimal("100").compareTo(settings.taxPercent("experience")));
        assertEquals(BigDecimal.ZERO, settings.taxPercent("playerpoints"));
        assertEquals(new BigDecimal("12.125"), settings.taxPercent("tickets"));
    }

    // Each would pass a 0 to 100 check as a BigDecimal, but fail or explode in the tax arithmetic
    @ParameterizedTest
    @ValueSource(strings = {"1e-2147483647", "1E2", "0.0005", "-5", "+5", "5.", ".5", "1000", "NaN", "0x10"})
    void taxPercentOtherThanAPlainDecimalIsZero(String value) {
        YamlConfiguration config = new YamlConfiguration();
        config.set("currencies.money.tax-percent", value);
        assertEquals(BigDecimal.ZERO, Settings.load(config, LOGGER).taxPercent("money"));
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
