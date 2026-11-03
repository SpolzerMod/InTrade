package me.spolzer.intrade.config;

import java.math.BigDecimal;
import java.util.Collections;
import java.util.EnumMap;
import java.util.EnumSet;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.logging.Logger;
import java.util.regex.Pattern;
import org.bukkit.Material;
import org.bukkit.configuration.ConfigurationSection;

/** Values of config.yml. A new instance is created on every reload. */
public record Settings(
        String language,
        String defaultLanguage,
        boolean openToEveryone,
        long requestExpireMillis,
        long requestCooldownMillis,
        boolean sneakClick,
        double maxDistance,
        boolean sameWorld,
        long changeLockMillis,
        long highlightMillis,
        int confirmSeconds,
        long maxTradeMillis,
        boolean historyEnabled,
        int historyKeepDays,
        long pairCooldownMillis,
        Set<String> currencies,
        Map<String, BigDecimal> taxPercents,
        Set<Material> blockedItems,
        Set<String> disabledWorlds,
        Map<TradeSound, SoundEffect> sounds,
        Database database) {

    private static final BigDecimal HUNDRED = BigDecimal.valueOf(100);
    private static final Pattern TAX_PERCENT = Pattern.compile("\\d{1,3}(\\.\\d{1,3})?");

    /** Ids of the currencies InTrade supports itself. They are enabled unless the config turns them off. */
    public static final Set<String> BUILT_IN_CURRENCIES = Set.of("money", "experience", "playerpoints");

    public static Settings load(ConfigurationSection config, Logger logger) {
        // Enabled currencies in the order of the config, which is also the order of the currencies of other plugins
        Set<String> ids = new LinkedHashSet<>();
        ConfigurationSection section = config.getConfigurationSection("currencies");
        if (section != null) ids.addAll(section.getKeys(false));
        ids.addAll(BUILT_IN_CURRENCIES);
        Set<String> currencies = new LinkedHashSet<>();
        Map<String, BigDecimal> taxPercents = new HashMap<>();
        for (String id : ids) {
            String path = "currencies." + id;
            if (config.getBoolean(path + ".enabled", BUILT_IN_CURRENCIES.contains(id))) currencies.add(id);
            BigDecimal tax = taxPercent(config, path + ".tax-percent", logger);
            if (tax.signum() > 0) taxPercents.put(id, tax);
        }
        Set<Material> blocked = EnumSet.noneOf(Material.class);
        for (String name : config.getStringList("blocked-items")) {
            Material material = Material.matchMaterial(name);
            if (material != null) blocked.add(material);
            else logger.warning("Unknown material in blocked-items: " + name);
        }
        Set<String> worlds = new HashSet<>();
        for (String world : config.getStringList("disabled-worlds")) worlds.add(world.toLowerCase(Locale.ROOT));

        return new Settings(
                config.getString("language", "auto"),
                config.getString("default-language", "en"),
                config.getBoolean("permissions.open-to-everyone", true),
                Math.max(5, config.getLong("requests.expire-seconds", 60)) * 1000L,
                Math.max(0, config.getLong("requests.cooldown-seconds", 5)) * 1000L,
                config.getBoolean("requests.sneak-click", true),
                Math.max(0, config.getDouble("requests.max-distance", 0)),
                config.getBoolean("requests.same-world", false),
                Math.max(0, config.getLong("safety.change-lock-seconds", 3)) * 1000L,
                Math.max(0, config.getLong("safety.highlight-seconds", 6)) * 1000L,
                Math.max(0, config.getInt("safety.confirm-seconds", 3)),
                Math.max(0, config.getLong("safety.max-trade-seconds", 300)) * 1000L,
                config.getBoolean("history.enabled", true),
                Math.max(0, config.getInt("history.keep-days", 90)),
                Math.max(0, config.getLong("stats.pair-cooldown-minutes", 60)) * 60_000L,
                Collections.unmodifiableSet(currencies),
                Map.copyOf(taxPercents),
                Set.copyOf(blocked),
                Set.copyOf(worlds),
                sounds(config, logger),
                Database.load(config, logger));
    }

    private static BigDecimal taxPercent(ConfigurationSection config, String path, Logger logger) {
        Object value = config.get(path);
        if (value == null) return BigDecimal.ZERO;
        String text = value.toString().trim();
        // Plain decimals only: BigDecimal alone would also accept exponents like 1e-2147483647
        if (!TAX_PERCENT.matcher(text).matches()) {
            logger.warning("Invalid number in " + path + ": " + value + ", expected a number such as 2.5 with at most "
                    + "3 decimal places. Using 0");
            return BigDecimal.ZERO;
        }
        BigDecimal percent = new BigDecimal(text);
        if (percent.compareTo(HUNDRED) > 0) {
            logger.warning(path + " must be between 0 and 100, got " + value + ". Using 100");
            percent = HUNDRED;
        }
        return percent.stripTrailingZeros();
    }

    private static Map<TradeSound, SoundEffect> sounds(ConfigurationSection config, Logger logger) {
        float volume = (float) Math.clamp(config.getDouble("sounds.volume", 0.6), 0, 1);
        Map<TradeSound, SoundEffect> sounds = new EnumMap<>(TradeSound.class);
        for (TradeSound sound : TradeSound.values()) {
            String value = config.getString("sounds." + sound.key(), sound.fallback);
            SoundEffect effect = SoundEffect.parse(value, volume);
            if (effect == null) {
                logger.warning("Invalid pitch in sounds." + sound.key() + ": " + value);
                effect = SoundEffect.NONE;
            }
            sounds.put(sound, effect);
        }
        return Map.copyOf(sounds);
    }

    /** Built-in currencies are enabled by default, currencies of other plugins only when the config enables them. */
    public boolean currencyEnabled(String id) { return currencies.contains(id); }
    /** Percent of every amount of the currency that is taken from the receiver, 0 to 100. */
    public BigDecimal taxPercent(String id) { return taxPercents.getOrDefault(id, BigDecimal.ZERO); }
    public boolean isBlocked(Material material) { return blockedItems.contains(material); }
    public boolean isWorldDisabled(String world) { return disabledWorlds.contains(world.toLowerCase(Locale.ROOT)); }
    public SoundEffect sound(TradeSound sound) { return sounds.getOrDefault(sound, SoundEffect.NONE); }

    public record Database(boolean mysql, String serverId, String host, int port, String name, String user,
                           String password, int poolSize, Map<String, String> properties) {

        static Database load(ConfigurationSection config, Logger logger) {
            String type = config.getString("storage.type", "sqlite").toLowerCase(Locale.ROOT);
            boolean mysql = type.equals("mysql") || type.equals("mariadb");
            if (!mysql && !type.equals("sqlite")) logger.warning("Unknown storage.type '" + type + "', using sqlite");
            Map<String, String> properties = new LinkedHashMap<>();
            ConfigurationSection extra = config.getConfigurationSection("storage.mysql.properties");
            if (extra != null) {
                for (String key : extra.getKeys(false)) properties.put(key, extra.getString(key));
            }
            return new Database(mysql,
                    config.getString("storage.server-id", "main"),
                    config.getString("storage.mysql.host", "localhost"),
                    config.getInt("storage.mysql.port", 3306),
                    config.getString("storage.mysql.database", "minecraft"),
                    config.getString("storage.mysql.user", "root"),
                    config.getString("storage.mysql.password", ""),
                    Math.max(2, config.getInt("storage.mysql.pool-size", 4)),
                    Collections.unmodifiableMap(properties));
        }

        @Override
        public String toString() {
            return "Database[" + (mysql ? "mysql " + host + ":" + port + "/" + name : "sqlite") + ", server " + serverId + "]";
        }
    }
}
