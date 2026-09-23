package me.spolzer.intrade.storage;

import com.zaxxer.hikari.HikariConfig;
import com.zaxxer.hikari.HikariDataSource;
import java.io.File;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.logging.Level;
import java.util.logging.Logger;
import me.spolzer.intrade.api.TraderStats;
import me.spolzer.intrade.config.Settings;
import org.bukkit.inventory.ItemStack;

// All writes run on a single thread to keep escrow and mail updates in order
public final class TradeStorage {
    private static final List<String> SQLITE_SCHEMA = List.of("""
            CREATE TABLE IF NOT EXISTS intrade_trades (
                id INTEGER PRIMARY KEY AUTOINCREMENT,
                time INTEGER NOT NULL,
                a_uuid TEXT NOT NULL,
                a_name TEXT NOT NULL,
                b_uuid TEXT NOT NULL,
                b_name TEXT NOT NULL,
                a_items BLOB,
                b_items BLOB,
                a_currencies TEXT NOT NULL,
                b_currencies TEXT NOT NULL
            )""",
            "CREATE INDEX IF NOT EXISTS intrade_trades_a ON intrade_trades (a_uuid, time)",
            "CREATE INDEX IF NOT EXISTS intrade_trades_b ON intrade_trades (b_uuid, time)",
            """
            CREATE TABLE IF NOT EXISTS intrade_escrow (
                server TEXT NOT NULL,
                owner TEXT NOT NULL,
                items BLOB NOT NULL,
                PRIMARY KEY (server, owner)
            )""",
            """
            CREATE TABLE IF NOT EXISTS intrade_mail (
                id INTEGER PRIMARY KEY AUTOINCREMENT,
                server TEXT NOT NULL,
                owner TEXT NOT NULL,
                items BLOB NOT NULL
            )""",
            "CREATE INDEX IF NOT EXISTS intrade_mail_owner ON intrade_mail (server, owner)",
            // Separate from history so that cleanup does not reset trade counts
            """
            CREATE TABLE IF NOT EXISTS intrade_stats (
                uuid TEXT PRIMARY KEY,
                name TEXT NOT NULL,
                trades INTEGER NOT NULL
            )""",
            "CREATE INDEX IF NOT EXISTS intrade_stats_trades ON intrade_stats (trades DESC)");

    // MEDIUMBLOB: BLOB is limited to 64 KB, not enough for 16 filled shulker boxes
    private static final List<String> MYSQL_SCHEMA = List.of("""
            CREATE TABLE IF NOT EXISTS intrade_trades (
                id BIGINT AUTO_INCREMENT PRIMARY KEY,
                time BIGINT NOT NULL,
                a_uuid CHAR(36) NOT NULL,
                a_name VARCHAR(64) NOT NULL,
                b_uuid CHAR(36) NOT NULL,
                b_name VARCHAR(64) NOT NULL,
                a_items MEDIUMBLOB,
                b_items MEDIUMBLOB,
                a_currencies TEXT NOT NULL,
                b_currencies TEXT NOT NULL,
                INDEX intrade_trades_a (a_uuid, time),
                INDEX intrade_trades_b (b_uuid, time)
            )""", """
            CREATE TABLE IF NOT EXISTS intrade_escrow (
                server VARCHAR(64) NOT NULL,
                owner CHAR(36) NOT NULL,
                items MEDIUMBLOB NOT NULL,
                PRIMARY KEY (server, owner)
            )""", """
            CREATE TABLE IF NOT EXISTS intrade_mail (
                id BIGINT AUTO_INCREMENT PRIMARY KEY,
                server VARCHAR(64) NOT NULL,
                owner CHAR(36) NOT NULL,
                items MEDIUMBLOB NOT NULL,
                INDEX intrade_mail_owner (server, owner)
            )""", """
            CREATE TABLE IF NOT EXISTS intrade_stats (
                uuid CHAR(36) PRIMARY KEY,
                name VARCHAR(64) NOT NULL,
                trades INT NOT NULL,
                INDEX intrade_stats_trades (trades)
            )""");

    private final Settings.Database settings;
    private final File file;
    private final Logger logger;
    private final String server;
    private final ExecutorService writer = Executors.newSingleThreadExecutor(task -> daemon(task, "InTrade-Storage"));
    private ExecutorService reader;
    private HikariDataSource pool;

    public TradeStorage(Settings.Database settings, File file, Logger logger) {
        this.settings = settings;
        this.file = file;
        this.logger = logger;
        this.server = settings.serverId;
    }

    public int open() throws SQLException {
        HikariConfig config = new HikariConfig();
        config.setPoolName("InTrade");
        if (settings.mysql) {
            config.setJdbcUrl("jdbc:mysql://" + settings.host + ":" + settings.port + "/" + settings.name);
            config.setDriverClassName("com.mysql.cj.jdbc.Driver");
            config.setUsername(settings.user);
            config.setPassword(settings.password);
            config.setMaximumPoolSize(settings.poolSize);
            settings.properties.forEach(config::addDataSourceProperty);
            reader = Executors.newFixedThreadPool(settings.poolSize - 1, task -> daemon(task, "InTrade-Storage-Read"));
        } else {
            config.setJdbcUrl("jdbc:sqlite:" + file.getAbsolutePath());
            config.setDriverClassName("org.sqlite.JDBC");
            config.setMaximumPoolSize(1);
            reader = writer;
        }
        pool = new HikariDataSource(config);

        try (Connection c = pool.getConnection()) {
            try (Statement st = c.createStatement()) {
                if (!settings.mysql) st.execute("PRAGMA journal_mode=WAL");
                for (String sql : settings.mysql ? MYSQL_SCHEMA : SQLITE_SCHEMA) st.execute(sql);
            }
            return transaction(c, () -> recoverEscrow(c));
        }
    }

    // Only rows of this server: other servers sharing the database may have trades in progress
    private int recoverEscrow(Connection c) throws SQLException {
        int recovered;
        try (PreparedStatement copy = c.prepareStatement(
                "INSERT INTO intrade_mail (server, owner, items) SELECT server, owner, items FROM intrade_escrow WHERE server = ?")) {
            copy.setString(1, server);
            recovered = copy.executeUpdate();
        }
        try (PreparedStatement delete = c.prepareStatement("DELETE FROM intrade_escrow WHERE server = ?")) {
            delete.setString(1, server);
            delete.executeUpdate();
        }
        return recovered;
    }

    public void close() {
        writer.shutdown();
        if (reader != null && reader != writer) reader.shutdown();
        try {
            if (!writer.awaitTermination(10, TimeUnit.SECONDS)) logger.warning("Timed out waiting for pending database writes");
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
        if (pool != null) pool.close();
    }

    public void purgeOlderThan(int days) {
        if (days <= 0) return;
        long cutoff = System.currentTimeMillis() - TimeUnit.DAYS.toMillis(days);
        write("Could not purge old trades", c -> {
            try (PreparedStatement ps = c.prepareStatement("DELETE FROM intrade_trades WHERE time < ?")) {
                ps.setLong(1, cutoff);
                int removed = ps.executeUpdate();
                if (removed > 0) logger.info("Removed " + removed + " trades older than " + days + " days");
            }
        });
    }

    public void saveEscrow(UUID owner, List<ItemStack> items) {
        if (items.isEmpty()) {
            clearEscrow(owner);
            return;
        }
        byte[] data = ItemStack.serializeItemsAsBytes(items);
        String upsert = settings.mysql
                ? "INSERT INTO intrade_escrow (server, owner, items) VALUES (?, ?, ?)"
                        + " ON DUPLICATE KEY UPDATE items = VALUES(items)"
                : "INSERT INTO intrade_escrow (server, owner, items) VALUES (?, ?, ?)"
                        + " ON CONFLICT (server, owner) DO UPDATE SET items = excluded.items";
        write("Could not save escrow for " + owner + ", the offer is not protected against a crash", c -> {
            try (PreparedStatement ps = c.prepareStatement(upsert)) {
                ps.setString(1, server);
                ps.setString(2, owner.toString());
                ps.setBytes(3, data);
                ps.executeUpdate();
            }
        });
    }

    public void clearEscrow(UUID owner) {
        write("Could not clear escrow for " + owner + ", these items may be returned twice after a restart", c -> {
            try (PreparedStatement ps = c.prepareStatement("DELETE FROM intrade_escrow WHERE server = ? AND owner = ?")) {
                ps.setString(1, server);
                ps.setString(2, owner.toString());
                ps.executeUpdate();
            }
        });
    }

    public void addMail(UUID owner, List<ItemStack> items) {
        if (items.isEmpty()) return;
        byte[] data = ItemStack.serializeItemsAsBytes(items);
        String lost = items.stream().map(item -> item.getAmount() + " " + item.getType()).toList().toString();
        write("Could not save trade mail for " + owner + ", lost items: " + lost, c -> {
            try (PreparedStatement ps = c.prepareStatement("INSERT INTO intrade_mail (server, owner, items) VALUES (?, ?, ?)")) {
                ps.setString(1, server);
                ps.setString(2, owner.toString());
                ps.setBytes(3, data);
                ps.executeUpdate();
            }
        });
    }

    public CompletableFuture<List<ItemStack>> takeMail(UUID owner) {
        return query(writer, "Could not load trade mail of " + owner, c -> transaction(c, () -> {
            List<ItemStack> items = new ArrayList<>();
            try (PreparedStatement select = c.prepareStatement("SELECT items FROM intrade_mail WHERE server = ? AND owner = ? ORDER BY id")) {
                select.setString(1, server);
                select.setString(2, owner.toString());
                try (ResultSet rs = select.executeQuery()) {
                    while (rs.next()) items.addAll(readItems(rs.getBytes(1)));
                }
            }
            try (PreparedStatement delete = c.prepareStatement("DELETE FROM intrade_mail WHERE server = ? AND owner = ?")) {
                delete.setString(1, server);
                delete.setString(2, owner.toString());
                delete.executeUpdate();
            }
            return items;
        }));
    }

    public CompletableFuture<Void> saveTrade(TradeRecord record, boolean history) {
        byte[] aItems = ItemStack.serializeItemsAsBytes(record.aItems());
        byte[] bItems = ItemStack.serializeItemsAsBytes(record.bItems());
        String count = settings.mysql
                ? "INSERT INTO intrade_stats (uuid, name, trades) VALUES (?, ?, 1)"
                        + " ON DUPLICATE KEY UPDATE trades = trades + 1, name = VALUES(name)"
                : "INSERT INTO intrade_stats (uuid, name, trades) VALUES (?, ?, 1)"
                        + " ON CONFLICT (uuid) DO UPDATE SET trades = trades + 1, name = excluded.name";
        return query(writer, "Could not save the trade between " + record.aName() + " and " + record.bName(), c -> transaction(c, () -> {
            if (history) {
                try (PreparedStatement ps = c.prepareStatement("""
                        INSERT INTO intrade_trades (time, a_uuid, a_name, b_uuid, b_name, a_items, b_items, a_currencies, b_currencies)
                        VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?)""")) {
                    ps.setLong(1, record.time());
                    ps.setString(2, record.aId().toString());
                    ps.setString(3, record.aName());
                    ps.setString(4, record.bId().toString());
                    ps.setString(5, record.bName());
                    ps.setBytes(6, aItems);
                    ps.setBytes(7, bItems);
                    ps.setString(8, writeCurrencies(record.aCurrencies()));
                    ps.setString(9, writeCurrencies(record.bCurrencies()));
                    ps.executeUpdate();
                }
            }
            try (PreparedStatement ps = c.prepareStatement(count)) {
                ps.setString(1, record.aId().toString());
                ps.setString(2, record.aName());
                ps.addBatch();
                ps.setString(1, record.bId().toString());
                ps.setString(2, record.bName());
                ps.addBatch();
                ps.executeBatch();
            }
            return null;
        }));
    }

    public CompletableFuture<List<TradeRecord>> history(UUID player, int offset, int limit) {
        return query(reader, "Could not load trade history of " + player, c -> {
            List<TradeRecord> records = new ArrayList<>();
            try (PreparedStatement ps = c.prepareStatement(
                    "SELECT * FROM intrade_trades WHERE a_uuid = ? OR b_uuid = ? ORDER BY time DESC LIMIT ? OFFSET ?")) {
                ps.setString(1, player.toString());
                ps.setString(2, player.toString());
                ps.setInt(3, limit);
                ps.setInt(4, offset);
                try (ResultSet rs = ps.executeQuery()) {
                    int unreadable = 0;
                    while (rs.next()) {
                        List<ItemStack> aItems = List.of();
                        List<ItemStack> bItems = List.of();
                        try {
                            aItems = readItems(rs.getBytes("a_items"));
                            bItems = readItems(rs.getBytes("b_items"));
                        } catch (IllegalArgumentException e) {
                            // Saved by a server on a newer Minecraft version that shares this database
                            unreadable++;
                        }
                        records.add(new TradeRecord(rs.getLong("time"),
                                UUID.fromString(rs.getString("a_uuid")), rs.getString("a_name"),
                                UUID.fromString(rs.getString("b_uuid")), rs.getString("b_name"),
                                aItems, bItems,
                                readCurrencies(rs.getString("a_currencies")), readCurrencies(rs.getString("b_currencies"))));
                    }
                    if (unreadable > 0) {
                        logger.warning(unreadable + " trade(s) of " + player + " were saved on a newer Minecraft version"
                                + " and are shown without items");
                    }
                }
            }
            return records;
        });
    }

    public CompletableFuture<Integer> tradeCount(UUID player) {
        return query(reader, "Could not count trades of " + player, c -> {
            try (PreparedStatement ps = c.prepareStatement("SELECT trades FROM intrade_stats WHERE uuid = ?")) {
                ps.setString(1, player.toString());
                try (ResultSet rs = ps.executeQuery()) {
                    return rs.next() ? rs.getInt(1) : 0;
                }
            }
        });
    }

    public CompletableFuture<List<TraderStats>> topTraders(int limit) {
        return query(reader, "Could not load the top traders", c -> {
            List<TraderStats> top = new ArrayList<>();
            try (PreparedStatement ps = c.prepareStatement("SELECT uuid, name, trades FROM intrade_stats ORDER BY trades DESC, name LIMIT ?")) {
                ps.setInt(1, limit);
                try (ResultSet rs = ps.executeQuery()) {
                    while (rs.next()) top.add(new TraderStats(UUID.fromString(rs.getString(1)), rs.getString(2), rs.getInt(3)));
                }
            }
            return top;
        });
    }

    public CompletableFuture<UUID> findPlayer(String name) {
        return query(reader, "Could not look up player " + name, c -> {
            try (PreparedStatement ps = c.prepareStatement("SELECT uuid FROM intrade_stats WHERE LOWER(name) = LOWER(?)")) {
                ps.setString(1, name);
                try (ResultSet rs = ps.executeQuery()) {
                    return rs.next() ? UUID.fromString(rs.getString(1)) : null;
                }
            }
        });
    }

    private static List<ItemStack> readItems(byte[] data) {
        if (data == null || data.length == 0) return List.of();
        return Arrays.asList(ItemStack.deserializeItemsFromBytes(data));
    }

    private static String writeCurrencies(Map<String, Double> currencies) {
        StringBuilder out = new StringBuilder();
        currencies.forEach((id, amount) -> {
            if (out.length() > 0) out.append(';');
            out.append(id).append('=').append(amount);
        });
        return out.toString();
    }

    private static Map<String, Double> readCurrencies(String text) {
        Map<String, Double> currencies = new LinkedHashMap<>();
        if (text == null || text.isEmpty()) return currencies;
        for (String entry : text.split(";")) {
            int eq = entry.indexOf('=');
            if (eq > 0) currencies.put(entry.substring(0, eq), Double.parseDouble(entry.substring(eq + 1)));
        }
        return currencies;
    }

    private static <T> T transaction(Connection c, Work<T> work) throws SQLException {
        c.setAutoCommit(false);
        try {
            T result = work.run();
            c.commit();
            return result;
        } catch (SQLException | RuntimeException e) {
            c.rollback();
            throw e;
        } finally {
            c.setAutoCommit(true);
        }
    }

    private void write(String failure, Update update) {
        writer.execute(() -> {
            try (Connection c = pool.getConnection()) {
                update.run(c);
            } catch (Exception e) {
                logger.log(Level.SEVERE, failure, e);
            }
        });
    }

    private <T> CompletableFuture<T> query(ExecutorService on, String failure, Query<T> query) {
        CompletableFuture<T> future = new CompletableFuture<>();
        on.execute(() -> {
            try (Connection c = pool.getConnection()) {
                future.complete(query.run(c));
            } catch (Exception e) {
                logger.log(Level.SEVERE, failure, e);
                future.completeExceptionally(e);
            }
        });
        return future;
    }

    private static Thread daemon(Runnable task, String name) {
        Thread thread = new Thread(task, name);
        thread.setDaemon(true);
        return thread;
    }

    private interface Work<T> { T run() throws SQLException; }
    private interface Update { void run(Connection c) throws SQLException; }
    private interface Query<T> { T run(Connection c) throws SQLException; }
}
