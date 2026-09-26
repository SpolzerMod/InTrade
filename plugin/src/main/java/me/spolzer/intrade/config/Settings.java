package me.spolzer.intrade.config;

import java.util.Collections;
import java.util.EnumMap;
import java.util.EnumSet;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.logging.Logger;
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
        boolean historyEnabled,
        int historyKeepDays,
        Set<String> currencies,
        Set<Material> blockedItems,
        Set<String> disabledWorlds,
        Map<TradeSound, SoundEffect> sounds,
        Database database) {

    public static Settings load(ConfigurationSection config, Logger logger) {
        Set<String> currencies = new HashSet<>();
        for (String id : List.of("money", "experience", "playerpoints")) {
            if (config.getBoolean("currencies." + id + ".enabled", true)) currencies.add(id);
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
                config.getBoolean("history.enabled", true),
                Math.max(0, config.getInt("history.keep-days", 90)),
                Set.copyOf(currencies),
                Set.copyOf(blocked),
                Set.copyOf(worlds),
                sounds(config, logger),
                Database.load(config, logger));
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

    public boolean currencyEnabled(String id) { return currencies.contains(id); }
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
