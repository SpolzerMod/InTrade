package me.spolzer.intrade.storage;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.File;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.logging.Logger;
import me.spolzer.intrade.config.Settings;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class TradeStorageTest {
    private static final UUID ALICE = UUID.fromString("00000000-0000-0000-0000-00000000000a");
    private static final UUID BOB = UUID.fromString("00000000-0000-0000-0000-00000000000b");
    private static final long HOUR = 3_600_000L;

    @TempDir
    File folder;
    private File file;
    private TradeStorage storage;

    @BeforeEach
    void open() throws SQLException {
        file = new File(folder, "trades.db");
        storage = storage("main");
    }

    @AfterEach
    void close() {
        storage.close(new NoCallbacks());
    }

    private TradeStorage storage(String server) throws SQLException {
        TradeStorage opened = new TradeStorage(new Settings.Database(false, server, "", 0, "", "", "", 2, Map.of()),
                file, Logger.getLogger("InTradeTest"));
        opened.open();
        return opened;
    }

    @Test
    void recoveryReturnsOnlyTheVersionInThePlayerFile() throws Exception {
        storage.saveOffer(ALICE, 1, bytes(1)).join();
        storage.saveOffer(ALICE, 2, bytes(2)).join();
        storage.saveOffer(ALICE, 3, bytes(3)).join();

        assertTrue(storage.offerToMail(ALICE, 2).join());

        assertEquals(List.of(), offers("main", ALICE));
        List<byte[]> mail = mail("main", ALICE);
        assertEquals(1, mail.size());
        assertArrayEquals(bytes(2), mail.getFirst());
    }

    @Test
    void playerFileWithoutVersionRecoversNothing() throws Exception {
        storage.saveOffer(ALICE, 5, bytes(5)).join();

        assertFalse(storage.offerToMail(ALICE, 0).join());

        assertEquals(List.of(), offers("main", ALICE));
        assertEquals(0, mail("main", ALICE).size());
    }

    @Test
    void completedTradeLeavesWhatEachPlayerReceives() throws Exception {
        storage.saveOffer(ALICE, 10, bytes(10)).join();
        storage.saveOffer(BOB, 20, bytes(20)).join();

        storage.swapOffers(ALICE, 10, bytes(20), BOB, 20, bytes(10)).join();

        assertArrayEquals(bytes(20), offer("main", ALICE, 10));
        assertArrayEquals(bytes(10), offer("main", BOB, 20));
    }

    @Test
    void savedFileLetsOlderVersionsBeDeleted() throws Exception {
        storage.saveOffer(ALICE, 1, bytes(1)).join();
        storage.saveOffer(ALICE, 2, bytes(2)).join();
        storage.dropOffersExcept(ALICE, 2);
        storage.clearOffers(BOB);
        storage.saveOffer(BOB, 7, bytes(7)).join();

        assertEquals(List.of(2L), offers("main", ALICE));
    }

    @Test
    void offersOfAnotherServerAreNotTouched() throws Exception {
        TradeStorage survival = storage("survival");
        try {
            survival.saveOffer(ALICE, 1, bytes(1)).join();
            storage.saveOffer(ALICE, 2, bytes(2)).join();

            storage.offerToMail(ALICE, 2).join();

            assertEquals(List.of(1L), offers("survival", ALICE));
            assertEquals(0, mail("survival", ALICE).size());
        } finally {
            survival.close(new NoCallbacks());
        }
    }

    @Test
    void samePairIsCountedOncePerCooldown() throws Exception {
        long start = 1_000_000L;
        assertTrue(storage.count(ALICE, "Alice", BOB, "Bob", start, HOUR).join());
        assertFalse(storage.count(BOB, "Bob", ALICE, "Alice", start + HOUR / 2, HOUR).join());
        assertTrue(storage.count(ALICE, "Alice", BOB, "Bob", start + HOUR, HOUR).join());

        assertEquals(2, storage.tradeCount(ALICE).join());
        assertEquals(2, storage.tradeCount(BOB).join());
    }

    @Test
    void otherPairsAndZeroCooldownAreAlwaysCounted() throws Exception {
        UUID carol = UUID.randomUUID();
        assertTrue(storage.count(ALICE, "Alice", BOB, "Bob", 0, HOUR).join());
        assertTrue(storage.count(ALICE, "Alice", carol, "Carol", 1, HOUR).join());
        assertTrue(storage.count(ALICE, "Alice", BOB, "Bob", 2, 0).join());

        assertEquals(3, storage.tradeCount(ALICE).join());
        assertEquals("Alice", storage.topTraders(1).join().getFirst().name());
    }

    private static byte[] bytes(int marker) {
        return new byte[] {(byte) marker, 42};
    }

    private Connection connect() throws SQLException {
        return DriverManager.getConnection("jdbc:sqlite:" + file.getAbsolutePath());
    }

    private List<Long> offers(String server, UUID owner) throws SQLException {
        List<Long> versions = new ArrayList<>();
        try (Connection c = connect();
             PreparedStatement ps = c.prepareStatement("SELECT version FROM intrade_offers WHERE server = ? AND owner = ? ORDER BY version")) {
            ps.setString(1, server);
            ps.setString(2, owner.toString());
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) versions.add(rs.getLong(1));
            }
        }
        return versions;
    }

    private byte[] offer(String server, UUID owner, long version) throws SQLException {
        try (Connection c = connect();
             PreparedStatement ps = c.prepareStatement("SELECT items FROM intrade_offers WHERE server = ? AND owner = ? AND version = ?")) {
            ps.setString(1, server);
            ps.setString(2, owner.toString());
            ps.setLong(3, version);
            try (ResultSet rs = ps.executeQuery()) {
                return rs.next() ? rs.getBytes(1) : null;
            }
        }
    }

    private List<byte[]> mail(String server, UUID owner) throws SQLException {
        List<byte[]> items = new ArrayList<>();
        try (Connection c = connect();
             PreparedStatement ps = c.prepareStatement("SELECT items FROM intrade_mail WHERE server = ? AND owner = ?")) {
            ps.setString(1, server);
            ps.setString(2, owner.toString());
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) items.add(rs.getBytes(1));
            }
        }
        return items;
    }

    private static final class NoCallbacks implements TradeStorage.MainThreadQueue {
        @Override public void drain() { }
        @Override public boolean isEmpty() { return true; }
    }
}
