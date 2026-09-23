package me.spolzer.intrade.config;

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
import org.bukkit.configuration.file.FileConfiguration;

public final class Settings {
    public final String language;
    public final String defaultLanguage;
    public final boolean openToEveryone;
    public final long requestExpireMillis;
    public final long requestCooldownMillis;
    public final boolean sneakClick;
    public final double maxDistance;
    public final boolean sameWorld;
    public final long changeLockMillis;
    public final long highlightMillis;
    public final int confirmSeconds;
    public final boolean historyEnabled;
    public final int historyKeepDays;
    public final Database database;

    private final Set<String> currencies = new HashSet<>();
    private final Set<Material> blockedItems = EnumSet.noneOf(Material.class);
    private final Set<String> disabledWorlds = new HashSet<>();

    public Settings(FileConfiguration config, Logger logger) {
        language = config.getString("language", "auto");
        defaultLanguage = config.getString("default-language", "en");
        openToEveryone = config.getBoolean("permissions.open-to-everyone", true);
        requestExpireMillis = Math.max(5, config.getLong("requests.expire-seconds", 60)) * 1000L;
        requestCooldownMillis = Math.max(0, config.getLong("requests.cooldown-seconds", 5)) * 1000L;
        sneakClick = config.getBoolean("requests.sneak-click", true);
        maxDistance = Math.max(0, config.getDouble("requests.max-distance", 0));
        sameWorld = config.getBoolean("requests.same-world", false);
        changeLockMillis = Math.max(0, config.getLong("safety.change-lock-seconds", 3)) * 1000L;
        highlightMillis = Math.max(0, config.getLong("safety.highlight-seconds", 6)) * 1000L;
        confirmSeconds = Math.max(0, config.getInt("safety.confirm-seconds", 3));
        historyEnabled = config.getBoolean("history.enabled", true);
        historyKeepDays = Math.max(0, config.getInt("history.keep-days", 90));
        database = new Database(config, logger);

        for (String id : List.of("money", "experience", "playerpoints")) {
            if (config.getBoolean("currencies." + id + ".enabled", true)) currencies.add(id);
        }
        for (String name : config.getStringList("blocked-items")) {
            Material material = Material.matchMaterial(name);
            if (material != null) blockedItems.add(material);
            else logger.warning("Unknown material in blocked-items: " + name);
        }
        for (String world : config.getStringList("disabled-worlds")) disabledWorlds.add(world.toLowerCase(Locale.ROOT));
    }

    public boolean currencyEnabled(String id) { return currencies.contains(id); }
    public boolean isBlocked(Material material) { return blockedItems.contains(material); }
    public boolean isWorldDisabled(String world) { return disabledWorlds.contains(world.toLowerCase(Locale.ROOT)); }

    public static final class Database {
        public final boolean mysql;
        public final String serverId;
        public final String host;
        public final int port;
        public final String name;
        public final String user;
        public final String password;
        public final int poolSize;
        public final Map<String, String> properties = new LinkedHashMap<>();

        Database(FileConfiguration config, Logger logger) {
            String type = config.getString("storage.type", "sqlite").toLowerCase(Locale.ROOT);
            mysql = type.equals("mysql") || type.equals("mariadb");
            if (!mysql && !type.equals("sqlite")) logger.warning("Unknown storage.type '" + type + "', using sqlite");
            serverId = config.getString("storage.server-id", "main");
            host = config.getString("storage.mysql.host", "localhost");
            port = config.getInt("storage.mysql.port", 3306);
            name = config.getString("storage.mysql.database", "minecraft");
            user = config.getString("storage.mysql.user", "root");
            password = config.getString("storage.mysql.password", "");
            poolSize = Math.max(2, config.getInt("storage.mysql.pool-size", 4));
            ConfigurationSection extra = config.getConfigurationSection("storage.mysql.properties");
            if (extra != null) {
                for (String key : extra.getKeys(false)) properties.put(key, extra.getString(key));
            }
        }
    }
}
