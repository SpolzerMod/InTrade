package me.spolzer.intrade.trade;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.math.BigDecimal;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.logging.Level;
import me.spolzer.intrade.FakeCurrency;
import me.spolzer.intrade.api.InTrade;
import me.spolzer.intrade.api.currency.TradeCurrency;
import me.spolzer.intrade.currency.Currency;
import org.bukkit.Material;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;
import org.bukkit.plugin.ServicePriority;
import org.junit.jupiter.api.Test;
import org.mockbukkit.mockbukkit.entity.PlayerMock;

class ExternalCurrencyTest extends TradeTestBase {

    @Test
    void externalCurrencyIsUsedOnlyWhenEnabled() {
        assertTrue(InTrade.get().currencies().containsAll(List.of("tokens", "tickets")));
        assertTrue(alice.hasPermission("intrade.currency.tokens"));

        register("credits");
        assertFalse(InTrade.get().currencies().contains("credits"));

        server.getServicesManager().unregister(TradeCurrency.class, tickets);
        assertFalse(InTrade.get().currencies().contains("tickets"));
        assertTrue(InTrade.get().currencies().contains("tokens"));
    }

    @Test
    void builtInAndInvalidIdsAreIgnored() {
        register("money");
        register("Not Valid");
        assertNull(plugin.currencies().byId("Not Valid"));
        // No Vault in the test, so money stays disabled instead of being replaced
        assertNull(plugin.currencies().byId("money"));
    }

    @Test
    void invalidIconOrScaleIsIgnored() throws Exception {
        Map<String, Object> config = new LinkedHashMap<>();
        config.put("currencies.noicon.enabled", true);
        config.put("currencies.huge.enabled", true);
        configure(config);
        server.getServicesManager().unregister(TradeCurrency.class, tickets);
        server.getServicesManager().register(TradeCurrency.class, new Variant("noicon", null, 0), provider, ServicePriority.Normal);
        server.getServicesManager().register(TradeCurrency.class, new Variant("huge", Material.EMERALD, 19), provider, ServicePriority.Normal);

        assertNull(plugin.currencies().byId("noicon"));
        assertNull(plugin.currencies().byId("huge"));
        assertTrue(logged(Level.WARNING, "noicon", "not an item"));
        assertTrue(logged(Level.WARNING, "huge", "scale 19"));
    }

    @Test
    void unregisteredCurrencyIsRemovedFromOpenTrades() {
        TradeSession session = open();
        TradeSide a = session.sideOf(alice);
        set(session, alice, "tickets", "3");
        set(session, alice, "tokens", "4");
        ready(session, alice);
        assertTrue(a.ready);

        server.getServicesManager().unregister(TradeCurrency.class, tickets);
        assertEquals(BigDecimal.ZERO, a.currency("tickets"));
        assertEquals(new BigDecimal("4"), a.currency("tokens"));
        assertFalse(a.ready);
    }

    @Test
    void currencyPushedOutOfTheWindowIsNotTraded() throws Exception {
        // experience, tokens and tickets fill the three buttons. credits comes before tickets in the config.
        Map<String, Object> config = new LinkedHashMap<>();
        config.put("currencies.tickets", null);
        config.put("currencies.credits.enabled", true);
        config.put("currencies.tickets.enabled", true);
        configure(config);
        assertEquals(List.of("experience", "tokens", "tickets"), plugin.currencies().active().stream().map(Currency::id).toList());

        TradeSession session = open();
        TradeSide a = session.sideOf(alice);
        set(session, alice, "tickets", "3");
        ready(session, alice);
        assertTrue(a.ready);

        register("credits");
        assertEquals(List.of("experience", "tokens", "credits"), plugin.currencies().active().stream().map(Currency::id).toList());
        assertFalse(InTrade.get().currencies().contains("tickets"));
        assertNull(plugin.currencies().byId("tickets"));
        assertTrue(logged(Level.WARNING, "not used: tickets"));
        assertEquals(BigDecimal.ZERO, a.currency("tickets"));
        assertFalse(a.ready);

        // Nothing is left to settle in tickets
        session.sideOf(bob).player.getInventory().setItem(0, new ItemStack(Material.DIAMOND));
        set(session, bob, "tokens", "2");
        ready(session);
        assertTrue(session.finished());
        assertEquals(List.of("tokens:settle"), calls);
    }

    @Test
    void answerForAReplacedProviderIsDropped() {
        TradeSession session = open();
        TradeSide a = session.sideOf(alice);
        TradeSide b = session.sideOf(bob);
        set(session, bob, "tickets", "1");
        ready(session, bob);
        typing(a);
        Currency asked = currency("tokens");

        // Same id, new provider
        server.getServicesManager().unregister(TradeCurrency.class, tokens);
        tokens.retired = true;
        FakeCurrency replacement = register("tokens");
        // The reload resets the confirmations, bob confirms again before alice answers
        assertFalse(b.ready);
        ready(session, bob);
        assertTrue(b.ready);

        session.amountEntered(a, asked, new BigDecimal("5"));
        assertEquals(0, tokens.retiredCalls);
        assertEquals(BigDecimal.ZERO, a.currency("tokens"));
        assertFalse(b.ready);
        assertEquals(TradeSide.Mode.MENU, a.mode);
        assertFalse(session.finished());

        // The new provider is used from now on
        set(session, alice, "tokens", "5");
        assertEquals(new BigDecimal("5"), a.currency("tokens"));
        assertEquals(0, tokens.retiredCalls);
        assertTrue(replacement.balanceOf(id(alice)).signum() > 0);
    }

    @Test
    void answerAfterUnregisterIsDropped() {
        TradeSession session = open();
        TradeSide a = session.sideOf(alice);
        typing(a);
        Currency asked = currency("tokens");
        server.getServicesManager().unregister(TradeCurrency.class, tokens);
        tokens.retired = true;

        session.amountEntered(a, asked, new BigDecimal("5"));
        assertEquals(0, tokens.retiredCalls);
        assertEquals(BigDecimal.ZERO, a.currency("tokens"));
        assertFalse(session.finished());
    }

    @Test
    void replacedProviderRemovesTheOfferedAmount() {
        TradeSession session = open();
        TradeSide a = session.sideOf(alice);
        set(session, alice, "tokens", "4");
        ready(session, alice);

        server.getServicesManager().unregister(TradeCurrency.class, tokens);
        tokens.retired = true;
        register("tokens");
        assertEquals(BigDecimal.ZERO, a.currency("tokens"));
        assertFalse(a.ready);
        assertEquals(0, tokens.retiredCalls);
    }

    @Test
    void failingBalanceStopsOnlyThatTrade() {
        PlayerMock carol = player("carol");
        PlayerMock dave = player("dave");
        TradeSession broken = open();
        TradeSession healthy = open(carol, dave);
        offerDiamonds(broken, broken.sideOf(alice));
        tokens.balanceFailsFor = id(alice);

        plugin.trades().tick();
        assertTrue(broken.finished());
        assertTrue(alice.getInventory().contains(Material.DIAMOND, 5));
        assertTrue(logged(Level.SEVERE, "alice", "bob", "balance()", "tokens", "Provider"));
        assertFalse(healthy.finished());

        set(healthy, carol, "tokens", "10");
        ready(healthy, carol, dave);
        assertTrue(healthy.finished());
        assertEquals(new BigDecimal("990"), tokens.balanceOf(id(carol)));
        assertEquals(new BigDecimal("1010"), tokens.balanceOf(id(dave)));
    }

    @Test
    void failingFormatStopsOnlyThatTrade() {
        PlayerMock carol = player("carol");
        PlayerMock dave = player("dave");
        TradeSession broken = open();
        TradeSession healthy = open(carol, dave);
        offerDiamonds(broken, broken.sideOf(alice));
        set(broken, alice, "tokens", "7");
        set(healthy, carol, "tokens", "5");
        tokens.formatFailsFor = new BigDecimal("7");
        drainMessages(alice, bob);

        plugin.trades().tick();
        assertTrue(broken.finished());
        assertTrue(alice.getInventory().contains(Material.DIAMOND, 5));
        assertTrue(logged(Level.SEVERE, "alice", "bob", "format()", "tokens"));
        assertTrue(drainMessages(alice).stream().anyMatch(message -> message.contains("items are returned")));
        assertFalse(healthy.finished());
        plugin.trades().tick();
        assertFalse(healthy.finished());
        assertEquals(new BigDecimal("5"), healthy.sideOf(carol).currency("tokens"));
    }

    @Test
    void failingSettleStopsOnlyThatTrade() {
        PlayerMock carol = player("carol");
        PlayerMock dave = player("dave");
        TradeSession broken = open();
        TradeSession healthy = open(carol, dave);
        offerDiamonds(broken, broken.sideOf(alice));
        set(broken, alice, "tokens", "7");
        set(healthy, carol, "tokens", "5");
        tokens.settleFailure = new IllegalStateException("database is gone");

        ready(broken);
        assertTrue(broken.finished());
        assertTrue(alice.getInventory().contains(Material.DIAMOND, 5));
        plugin.trades().tick();
        assertFalse(healthy.finished());

        tokens.settleFailure = null;
        ready(healthy, carol, dave);
        assertTrue(healthy.finished());
        assertEquals(new BigDecimal("995"), tokens.balanceOf(id(carol)));
    }

    // As askAmount: the trade window is closed while the player types an amount
    private static void typing(TradeSide side) {
        side.mode = TradeSide.Mode.INPUT;
        side.player.closeInventory();
    }

    // A currency with a chosen icon and scale, to test the checks at registration
    private record Variant(String id, Material icon, int scale) implements TradeCurrency {
        @Override public BigDecimal balance(Player player) { return BigDecimal.ZERO; }
        @Override public String format(BigDecimal amount) { return amount.toPlainString(); }
        @Override public boolean settle(UUID trade, List<Payment> payments) { return false; }
        @Override public void revert(UUID trade) {}
    }
}
