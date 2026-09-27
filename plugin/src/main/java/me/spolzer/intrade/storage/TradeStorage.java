package me.spolzer.intrade.storage;

import com.zaxxer.hikari.HikariConfig;
import com.zaxxer.hikari.HikariDataSource;
import java.io.File;
import java.math.BigDecimal;
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
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.logging.Level;
import java.util.logging.Logger;
import me.spolzer.intrade.api.TraderStats;
import me.spolzer.intrade.config.Settings;
import org.bukkit.inventory.ItemStack;

// All writes run on a single thread to keep offer and mail updates in order
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
            CREATE TABLE IF NOT EXISTS intrade_offers (
                server TEXT NOT NULL,
                owner TEXT NOT NULL,
                version INTEGER NOT NULL,
                items BLOB NOT NULL,
                PRIMARY KEY (server, owner, version)
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
            "CREATE INDEX IF NOT EXISTS intrade_stats_trades ON intrade_stats (trades DESC)",
            """
            CREATE TABLE IF NOT EXISTS intrade_pairs (
                a TEXT NOT NULL,
                b TEXT NOT NULL,
                time INTEGER NOT NULL,
                PRIMARY KEY (a, b)
            )""");

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
            CREATE TABLE IF NOT EXISTS intrade_offers (
                server VARCHAR(64) NOT NULL,
                owner CHAR(36) NOT NULL,
                version BIGINT NOT NULL,
                items MEDIUMBLOB NOT NULL,
                PRIMARY KEY (server, owner, version)
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
            )""", """
            CREATE TABLE IF NOT EXISTS intrade_pairs (
                a CHAR(36) NOT NULL,
                b CHAR(36) NOT NULL,
                time BIGINT NOT NULL,
                PRIMARY KEY (a, b)
            )""", ServerLock.SCHEMA);

    private static final long HEARTBEAT_MILLIS = TimeUnit.SECONDS.toMillis(5);

    private final Settings.Database settings;
    private final File file;
    private final Logger logger;
    private final String server;
    private final ExecutorService writer = Executors.newSingleThreadExecutor(task -> daemon(task, "InTrade-Storage"));
    private ExecutorService reader;
    private HikariDataSource pool;
    private ServerLock lock;

    public TradeStorage(Settings.Database settings, File file, Logger logger) {
        this.settings = settings;
        this.file = file;
        this.logger = logger;
        this.server = settings.serverId();
    }

    /**
     * Connects, creates the tables and, with MySQL, claims the server id.
     *
     * @return number of offers recovered from the table of InTrade 2.0
     * @throws IllegalStateException if another running server uses the same server id
     */
    public int open() throws SQLException {
        HikariConfig config = new HikariConfig();
        config.setPoolName("InTrade");
        if (settings.mysql()) {
            config.setJdbcUrl("jdbc:mysql://" + settings.host() + ":" + settings.port() + "/" + settings.name());
            config.setDriverClassName("com.mysql.cj.jdbc.Driver");
            config.setUsername(settings.user());
            config.setPassword(settings.password());
            config.setMaximumPoolSize(settings.poolSize());
            // Startup waits for the database, so an unreachable server should fail fast
            config.setConnectionTimeout(TimeUnit.SECONDS.toMillis(10));
            settings.properties().forEach(config::addDataSourceProperty);
            // Reads only wait for the network, the pool size limits how many run at once
            reader = Executors.newThreadPerTaskExecutor(Thread.ofVirtual().name("InTrade-Storage-Read-", 0).factory());
        } else {
            config.setJdbcUrl("jdbc:sqlite:" + file.getAbsolutePath());
            config.setDriverClassName("org.sqlite.JDBC");
            config.setMaximumPoolSize(1);
            reader = writer;
        }
        pool = new HikariDataSource(config);

        try (Connection c = pool.getConnection()) {
            try (Statement st = c.createStatement()) {
                if (!settings.mysql()) st.execute("PRAGMA journal_mode=WAL");
                for (String sql : settings.mysql() ? MYSQL_SCHEMA : SQLITE_SCHEMA) st.execute(sql);
            }
        }
        if (settings.mysql()) {
            lock = new ServerLock(pool, server, HEARTBEAT_MILLIS, logger);
            lock.claim();
        }
        try (Connection c = pool.getConnection()) {
            return transaction(c, () -> recoverLegacy(c));
        }
    }

    // InTrade 2.0 kept one offer per player without a version. Such rows are only left by a crash and are
    // returned as mail, as 2.0 did on startup.
    private int recoverLegacy(Connection c) throws SQLException {
        try (ResultSet tables = c.getMetaData().getTables(c.getCatalog(), null, "intrade_escrow", null)) {
            if (!tables.next()) return 0;
        }
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

    /**
     * Waits for pending writes and closes the connections.
     *
     * @param mainThread main thread callbacks of finished operations, which may start new writes
     */
    public void close(MainThreadQueue mainThread) {
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(10);
        try {
            do {
                mainThread.drain();
                writer.submit(() -> { }).get(Math.max(0, deadline - System.nanoTime()), TimeUnit.NANOSECONDS);
            } while (!mainThread.isEmpty());
        } catch (TimeoutException e) {
            logger.warning("Timed out waiting for pending database writes");
        } catch (ExecutionException e) {
            logger.log(Level.WARNING, "Could not wait for pending database writes", e);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
        writer.shutdown();
        if (reader != null && reader != writer) reader.shutdown();
        if (lock != null) lock.release();
        if (pool != null) pool.close();
    }

    /** Main thread callbacks, as seen by {@link #close}. */
    public interface MainThreadQueue {
        void drain();
        boolean isEmpty();
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

    public void purgePairs(long cooldownMillis) {
        long cutoff = System.currentTimeMillis() - cooldownMillis;
        write("Could not purge old trade pairs", c -> {
            try (PreparedStatement ps = c.prepareStatement("DELETE FROM intrade_pairs WHERE time < ?")) {
                ps.setLong(1, cutoff);
                ps.executeUpdate();
            }
        });
    }

    // Offers of open trades. Every change of an offer is saved as a new row with a new version before the items
    // move, and the player file stores the version it matches. After a crash only the row with that version is
    // returned, so the player file and the database always agree on where the items are.

    /** Saves an offer. The future completes once the row is committed. */
    public CompletableFuture<Void> saveOffer(UUID owner, long version, List<ItemStack> items) {
        return saveOffer(owner, version, ItemStack.serializeItemsAsBytes(items));
    }

    CompletableFuture<Void> saveOffer(UUID owner, long version, byte[] data) {
        return query(writer, "Could not save the trade offer of " + owner, c -> {
            upsertOffer(c, owner, version, data);
            return null;
        });
    }

    /**
     * Replaces the offers of both players in one transaction when a trade completes. Each row then holds what its
     * owner receives, so a crash after the commit completes the trade and a crash before it cancels the trade.
     */
    public CompletableFuture<Void> swapOffers(UUID a, long aVersion, List<ItemStack> toA, UUID b, long bVersion, List<ItemStack> toB) {
        return swapOffers(a, aVersion, ItemStack.serializeItemsAsBytes(toA), b, bVersion, ItemStack.serializeItemsAsBytes(toB));
    }

    CompletableFuture<Void> swapOffers(UUID a, long aVersion, byte[] toA, UUID b, long bVersion, byte[] toB) {
        return query(writer, "Could not save the completed trade of " + a + " and " + b, c -> transaction(c, () -> {
            upsertOffer(c, a, aVersion, toA);
            upsertOffer(c, b, bVersion, toB);
            return null;
        }));
    }

    private void upsertOffer(Connection c, UUID owner, long version, byte[] data) throws SQLException {
        String upsert = settings.mysql()
                ? "INSERT INTO intrade_offers (server, owner, version, items) VALUES (?, ?, ?, ?)"
                        + " ON DUPLICATE KEY UPDATE items = VALUES(items)"
                : "INSERT INTO intrade_offers (server, owner, version, items) VALUES (?, ?, ?, ?)"
                        + " ON CONFLICT (server, owner, version) DO UPDATE SET items = excluded.items";
        try (PreparedStatement ps = c.prepareStatement(upsert)) {
            ps.setString(1, server);
            ps.setString(2, owner.toString());
            ps.setLong(3, version);
            ps.setBytes(4, data);
            ps.executeUpdate();
        }
    }

    /** Deletes the other offers of the player. Only after the player file is saved with this version. */
    public void dropOffersExcept(UUID owner, long version) {
        write("Could not delete old trade offers of " + owner, c -> {
            try (PreparedStatement ps = c.prepareStatement("DELETE FROM intrade_offers WHERE server = ? AND owner = ? AND version <> ?")) {
                ps.setString(1, server);
                ps.setString(2, owner.toString());
                ps.setLong(3, version);
                ps.executeUpdate();
            }
        });
    }

    /** Deletes all offers of the player. Only after the player file is saved without a version. */
    public void clearOffers(UUID owner) {
        dropOffersExcept(owner, 0);
    }

    /**
     * Moves the offer with the given version to trade mail and deletes all other offers of the player, in one
     * transaction.
     *
     * @return whether an offer with this version existed
     */
    public CompletableFuture<Boolean> offerToMail(UUID owner, long version) {
        return query(writer, "Could not move the trade offer of " + owner + " to trade mail", c -> transaction(c, () -> {
            int moved;
            try (PreparedStatement copy = c.prepareStatement("INSERT INTO intrade_mail (server, owner, items)"
                    + " SELECT server, owner, items FROM intrade_offers WHERE server = ? AND owner = ? AND version = ?")) {
                copy.setString(1, server);
                copy.setString(2, owner.toString());
                copy.setLong(3, version);
                moved = copy.executeUpdate();
            }
            try (PreparedStatement delete = c.prepareStatement("DELETE FROM intrade_offers WHERE server = ? AND owner = ?")) {
                delete.setString(1, server);
                delete.setString(2, owner.toString());
                delete.executeUpdate();
            }
            return moved > 0;
        }));
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

    /**
     * Saves a completed trade and updates the trade counts.
     *
     * @param pairCooldownMillis minimum time between two counted trades of the same two players, 0 counts all
     * @return whether the trade was counted
     */
    public CompletableFuture<Boolean> saveTrade(TradeRecord record, boolean history, long pairCooldownMillis) {
        byte[] aItems = ItemStack.serializeItemsAsBytes(record.aItems());
        byte[] bItems = ItemStack.serializeItemsAsBytes(record.bItems());
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
            return count(c, record.aId(), record.aName(), record.bId(), record.bName(), record.time(), pairCooldownMillis);
        }));
    }

    CompletableFuture<Boolean> count(UUID a, String aName, UUID b, String bName, long time, long pairCooldownMillis) {
        return query(writer, "Could not count the trade between " + aName + " and " + bName,
                c -> transaction(c, () -> count(c, a, aName, b, bName, time, pairCooldownMillis)));
    }

    // Two players trading back and forth are counted once per cooldown, so the top cannot be raised
    // with a stream of trades of one dirt block
    private boolean count(Connection c, UUID a, String aName, UUID b, String bName, long time, long pairCooldownMillis)
            throws SQLException {
        if (pairCooldownMillis > 0) {
            String first = a.compareTo(b) < 0 ? a.toString() : b.toString();
            String second = a.compareTo(b) < 0 ? b.toString() : a.toString();
            try (PreparedStatement ps = c.prepareStatement("SELECT time FROM intrade_pairs WHERE a = ? AND b = ?")) {
                ps.setString(1, first);
                ps.setString(2, second);
                try (ResultSet rs = ps.executeQuery()) {
                    if (rs.next() && time - rs.getLong(1) < pairCooldownMillis) return false;
                }
            }
            String pair = settings.mysql()
                    ? "INSERT INTO intrade_pairs (a, b, time) VALUES (?, ?, ?) ON DUPLICATE KEY UPDATE time = VALUES(time)"
                    : "INSERT INTO intrade_pairs (a, b, time) VALUES (?, ?, ?) ON CONFLICT (a, b) DO UPDATE SET time = excluded.time";
            try (PreparedStatement ps = c.prepareStatement(pair)) {
                ps.setString(1, first);
                ps.setString(2, second);
                ps.setLong(3, time);
                ps.executeUpdate();
            }
        }
        String count = settings.mysql()
                ? "INSERT INTO intrade_stats (uuid, name, trades) VALUES (?, ?, 1)"
                        + " ON DUPLICATE KEY UPDATE trades = trades + 1, name = VALUES(name)"
                : "INSERT INTO intrade_stats (uuid, name, trades) VALUES (?, ?, 1)"
                        + " ON CONFLICT (uuid) DO UPDATE SET trades = trades + 1, name = excluded.name";
        try (PreparedStatement ps = c.prepareStatement(count)) {
            ps.setString(1, a.toString());
            ps.setString(2, aName);
            ps.addBatch();
            ps.setString(1, b.toString());
            ps.setString(2, bName);
            ps.addBatch();
            ps.executeBatch();
        }
        return true;
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

    // On the writer thread, so the result includes trades that are still queued for saving
    public CompletableFuture<Integer> tradeCount(UUID player) {
        return query(writer, "Could not count trades of " + player, c -> {
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

    // "money=1500.50;experience=30"
    private static String writeCurrencies(Map<String, BigDecimal> currencies) {
        StringBuilder out = new StringBuilder();
        currencies.forEach((id, amount) -> {
            if (out.length() > 0) out.append(';');
            out.append(id).append('=').append(amount.toPlainString());
        });
        return out.toString();
    }

    private static Map<String, BigDecimal> readCurrencies(String text) {
        Map<String, BigDecimal> currencies = new LinkedHashMap<>();
        if (text == null || text.isEmpty()) return currencies;
        for (String entry : text.split(";")) {
            int eq = entry.indexOf('=');
            if (eq > 0) currencies.put(entry.substring(0, eq), new BigDecimal(entry.substring(eq + 1)));
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
