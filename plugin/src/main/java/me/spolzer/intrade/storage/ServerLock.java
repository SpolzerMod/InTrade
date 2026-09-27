package me.spolzer.intrade.storage;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.UUID;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.logging.Level;
import java.util.logging.Logger;
import javax.sql.DataSource;

/**
 * Makes sure that only one running server uses a server id in a shared database.
 *
 * <p>Open offers and trade mail are stored per server id. If two running servers had the same id, a restart of one
 * of them would return the offers of trades that are still open on the other one, and the items would exist twice.
 *
 * <p>The owner of an id updates its heartbeat on a separate thread, so a lagging main thread does not matter. A new
 * server that finds a row of another instance waits a few heartbeats: if the row changes, the other server is alive
 * and the id is refused, otherwise the row was left by a crash and is taken over. Only the change of the value is
 * compared, so the clocks of different machines do not have to match.
 */
public final class ServerLock {
    static final String SCHEMA = """
            CREATE TABLE IF NOT EXISTS intrade_servers (
                server VARCHAR(64) NOT NULL PRIMARY KEY,
                instance CHAR(36) NOT NULL,
                heartbeat BIGINT NOT NULL
            )""";

    private final DataSource pool;
    private final String server;
    private final long intervalMillis;
    private final Logger logger;
    private final String instance = UUID.randomUUID().toString();
    private ScheduledExecutorService heartbeat;
    private volatile boolean lost;

    public ServerLock(DataSource pool, String server, long intervalMillis, Logger logger) {
        this.pool = pool;
        this.server = server;
        this.intervalMillis = intervalMillis;
        this.logger = logger;
    }

    /**
     * Claims the server id. Blocks for a few heartbeats if another instance has a row.
     *
     * @throws IllegalStateException if another running server uses the id
     */
    public void claim() throws SQLException {
        Row existing = read();
        if (existing == null && insert()) {
            start();
            return;
        }
        if (existing == null) existing = read();
        if (existing != null) {
            long wait = intervalMillis * 3;
            logger.info("Checking that no other running server uses server-id '" + server + "', this takes "
                    + TimeUnit.MILLISECONDS.toSeconds(wait) + " seconds");
            sleep(wait);
            Row later = read();
            boolean claimed = later == null ? insert() : later.equals(existing) && takeOver(existing);
            if (!claimed) {
                throw new IllegalStateException("storage.server-id '" + server + "' is used by another running server."
                        + " Every server that shares the database needs its own server-id in config.yml");
            }
        } else if (!insert()) {
            throw new IllegalStateException("Could not claim storage.server-id '" + server + "'");
        }
        start();
    }

    public void release() {
        if (heartbeat == null) return;
        heartbeat.shutdownNow();
        try (Connection c = pool.getConnection();
             PreparedStatement ps = c.prepareStatement("DELETE FROM intrade_servers WHERE server = ? AND instance = ?")) {
            ps.setString(1, server);
            ps.setString(2, instance);
            ps.executeUpdate();
        } catch (SQLException e) {
            logger.log(Level.WARNING, "Could not release server-id '" + server + "'", e);
        }
    }

    private void start() {
        heartbeat = Executors.newSingleThreadScheduledExecutor(task -> {
            Thread thread = new Thread(task, "InTrade-Heartbeat");
            thread.setDaemon(true);
            return thread;
        });
        heartbeat.scheduleWithFixedDelay(this::beat, intervalMillis, intervalMillis, TimeUnit.MILLISECONDS);
    }

    private void beat() {
        try (Connection c = pool.getConnection();
             PreparedStatement ps = c.prepareStatement("UPDATE intrade_servers SET heartbeat = ? WHERE server = ? AND instance = ?")) {
            ps.setLong(1, System.currentTimeMillis());
            ps.setString(2, server);
            ps.setString(3, instance);
            if (ps.executeUpdate() == 0 && !lost) {
                lost = true;
                logger.severe("Another server has taken over storage.server-id '" + server + "'."
                        + " Every server that shares the database needs its own server-id in config.yml");
            }
        } catch (SQLException e) {
            logger.log(Level.WARNING, "Could not update the heartbeat of server-id '" + server + "'", e);
        }
    }

    private Row read() throws SQLException {
        try (Connection c = pool.getConnection();
             PreparedStatement ps = c.prepareStatement("SELECT instance, heartbeat FROM intrade_servers WHERE server = ?")) {
            ps.setString(1, server);
            try (ResultSet rs = ps.executeQuery()) {
                return rs.next() ? new Row(rs.getString(1), rs.getLong(2)) : null;
            }
        }
    }

    // False if another server inserted the row at the same moment
    private boolean insert() {
        try (Connection c = pool.getConnection();
             PreparedStatement ps = c.prepareStatement("INSERT INTO intrade_servers (server, instance, heartbeat) VALUES (?, ?, ?)")) {
            ps.setString(1, server);
            ps.setString(2, instance);
            ps.setLong(3, System.currentTimeMillis());
            ps.executeUpdate();
            return true;
        } catch (SQLException e) {
            return false;
        }
    }

    private boolean takeOver(Row stale) throws SQLException {
        try (Connection c = pool.getConnection();
             PreparedStatement ps = c.prepareStatement(
                     "UPDATE intrade_servers SET instance = ?, heartbeat = ? WHERE server = ? AND instance = ? AND heartbeat = ?")) {
            ps.setString(1, instance);
            ps.setLong(2, System.currentTimeMillis());
            ps.setString(3, server);
            ps.setString(4, stale.instance());
            ps.setLong(5, stale.heartbeat());
            return ps.executeUpdate() == 1;
        }
    }

    private static void sleep(long millis) {
        try {
            Thread.sleep(millis);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }

    private record Row(String instance, long heartbeat) {
    }
}
