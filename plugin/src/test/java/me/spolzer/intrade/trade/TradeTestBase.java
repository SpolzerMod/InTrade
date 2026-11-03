package me.spolzer.intrade.trade;

import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.File;
import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.function.BooleanSupplier;
import java.util.logging.Handler;
import java.util.logging.Level;
import java.util.logging.LogRecord;
import me.spolzer.intrade.FakeCurrency;
import me.spolzer.intrade.InTradePlugin;
import me.spolzer.intrade.api.currency.TradeCurrency;
import me.spolzer.intrade.currency.Currency;
import org.bukkit.Material;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.inventory.ItemStack;
import org.bukkit.plugin.Plugin;
import org.bukkit.plugin.ServicePriority;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.mockbukkit.mockbukkit.MockBukkit;
import org.mockbukkit.mockbukkit.ServerMock;
import org.mockbukkit.mockbukkit.entity.PlayerMock;

/** Starts InTrade with two currencies of another plugin, tokens and tickets in this order, and two players. */
abstract class TradeTestBase {
    ServerMock server;
    InTradePlugin plugin;
    Plugin provider;
    final List<String> calls = new ArrayList<>();
    final List<LogRecord> logged = new CopyOnWriteArrayList<>();
    FakeCurrency tokens;
    FakeCurrency tickets;
    PlayerMock alice;
    PlayerMock bob;

    @BeforeEach
    void start() throws Exception {
        server = MockBukkit.mock();
        plugin = MockBukkit.load(InTradePlugin.class);
        provider = MockBukkit.createMockPlugin("Provider");
        plugin.getLogger().addHandler(new Handler() {
            @Override public void publish(LogRecord record) { if (record.getLevel().intValue() >= Level.WARNING.intValue()) logged.add(record); }
            @Override public void flush() {}
            @Override public void close() {}
        });
        Map<String, Object> config = new LinkedHashMap<>();
        config.put("safety.confirm-seconds", 0);
        config.put("safety.change-lock-seconds", 0);
        config.put("currencies.tokens.enabled", true);
        config.put("currencies.tickets.enabled", true);
        configure(config);
        tokens = register("tokens");
        tickets = register("tickets");
        alice = player("alice");
        bob = player("bob");
    }

    @AfterEach
    void stop() {
        MockBukkit.unmock();
    }

    PlayerMock player(String name) {
        PlayerMock player = new SavingPlayer(server, name);
        server.addPlayer(player);
        return player;
    }

    TradeSession open(PlayerMock first, PlayerMock second) {
        plugin.trades().start(first, second);
        TradeSession session = plugin.trades().of(first);
        assertNotNull(session);
        return session;
    }

    TradeSession open() {
        return open(alice, bob);
    }

    void offerDiamonds(TradeSession session, TradeSide side) {
        side.player.getInventory().setItem(0, new ItemStack(Material.DIAMOND, 5));
        session.offer(side, side.player.getInventory(), 0, 5);
        await(() -> side.items[0] != null);
    }

    // Through guard, as a click in the trade window
    void ready(TradeSession session, PlayerMock... players) {
        for (PlayerMock player : players.length > 0 ? players : new PlayerMock[] {alice, bob}) {
            session.guard(() -> session.toggleReady(session.sideOf(player)));
        }
    }

    void set(TradeSession session, PlayerMock player, String currency, String amount) {
        session.guard(() -> session.setCurrency(session.sideOf(player), currency(currency), new BigDecimal(amount)));
    }

    Currency currency(String id) {
        Currency currency = plugin.currencies().byId(id);
        assertNotNull(currency, id);
        return currency;
    }

    boolean logged(Level level, String... parts) {
        return logged.stream().anyMatch(record -> {
            if (record.getLevel() != level) return false;
            String message = record.getMessage();
            for (String part : parts) {
                if (!message.contains(part)) return false;
            }
            return true;
        });
    }

    List<String> drainMessages(PlayerMock... players) {
        List<String> messages = new ArrayList<>();
        for (PlayerMock player : players.length > 0 ? players : new PlayerMock[] {alice, bob}) {
            for (String message = player.nextMessage(); message != null; message = player.nextMessage()) messages.add(message);
        }
        return messages;
    }

    FakeCurrency register(String id) {
        FakeCurrency currency = new FakeCurrency(id, calls);
        server.getServicesManager().register(TradeCurrency.class, currency, provider, ServicePriority.Normal);
        return currency;
    }

    void configure(Map<String, Object> values) throws Exception {
        File file = new File(plugin.getDataFolder(), "config.yml");
        YamlConfiguration config = YamlConfiguration.loadConfiguration(file);
        values.forEach(config::set);
        config.save(file);
        CompletableFuture<Void> reload = plugin.reload();
        await(reload::isDone);
        reload.join();
    }

    // Runs ticks until the condition holds, database callbacks need a few
    void await(BooleanSupplier condition) {
        for (int i = 0; i < 400 && !condition.getAsBoolean(); i++) {
            server.getScheduler().performOneTick();
            try {
                Thread.sleep(5);
            } catch (InterruptedException e) {
                throw new AssertionError(e);
            }
        }
        assertTrue(condition.getAsBoolean(), "condition not reached");
    }

    static UUID id(PlayerMock player) {
        return player.getUniqueId();
    }

    private static final class SavingPlayer extends PlayerMock {
        SavingPlayer(ServerMock server, String name) {
            super(server, name, UUID.nameUUIDFromBytes(name.getBytes()));
        }

        @Override
        public void saveData() {
        }
    }
}
