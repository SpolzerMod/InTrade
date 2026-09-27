package me.spolzer.intrade.storage;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.io.File;
import java.sql.Connection;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.logging.Logger;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.sqlite.SQLiteConfig;
import org.sqlite.SQLiteDataSource;

class ServerLockTest {
    private static final long INTERVAL = 100;

    @TempDir
    File folder;
    private SQLiteDataSource database;

    @BeforeEach
    void createTable() throws SQLException {
        SQLiteConfig config = new SQLiteConfig();
        config.setBusyTimeout(5000);
        database = new SQLiteDataSource(config);
        database.setUrl("jdbc:sqlite:" + new File(folder, "lock.db").getAbsolutePath());
        try (Connection c = database.getConnection(); Statement st = c.createStatement()) {
            st.execute(ServerLock.SCHEMA);
        }
    }

    private ServerLock lock(String server) {
        return new ServerLock(database, server, INTERVAL, Logger.getLogger("InTradeTest"));
    }

    @Test
    void secondRunningServerWithTheSameIdIsRefused() throws SQLException {
        ServerLock first = lock("main");
        first.claim();
        try {
            assertThrows(IllegalStateException.class, () -> lock("main").claim());
        } finally {
            first.release();
        }
    }

    @Test
    void idIsFreeAfterACleanStop() throws SQLException {
        ServerLock first = lock("main");
        first.claim();
        first.release();

        ServerLock second = lock("main");
        assertDoesNotThrow(second::claim);
        second.release();
    }

    @Test
    void rowLeftByACrashIsTakenOver() throws SQLException {
        try (Connection c = database.getConnection(); Statement st = c.createStatement()) {
            st.execute("INSERT INTO intrade_servers (server, instance, heartbeat) VALUES ('main', 'crashed', 1)");
        }
        ServerLock restarted = lock("main");
        assertDoesNotThrow(restarted::claim);
        restarted.release();
    }

    @Test
    void differentIdsDoNotConflict() throws SQLException {
        ServerLock survival = lock("survival");
        ServerLock creative = lock("creative");
        survival.claim();
        assertDoesNotThrow(creative::claim);
        survival.release();
        creative.release();
    }
}
