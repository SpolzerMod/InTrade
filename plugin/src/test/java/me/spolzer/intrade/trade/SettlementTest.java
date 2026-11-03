package me.spolzer.intrade.trade;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.logging.Level;
import me.spolzer.intrade.FakeCurrency;
import me.spolzer.intrade.api.currency.TradeCurrency;
import me.spolzer.intrade.currency.BuiltInCurrency;
import org.bukkit.Material;
import org.bukkit.entity.Player;
import org.junit.jupiter.api.Test;

class SettlementTest extends TradeTestBase {

    @Test
    void externalCurrenciesSettleInOrderBeforeBuiltIn() {
        TradeSession session = open();
        TradeSide a = session.sideOf(alice);
        alice.setLevel(10);
        List<Integer> levels = new ArrayList<>();
        tokens.onSettle = () -> levels.add(alice.getLevel());
        tickets.onSettle = () -> levels.add(alice.getLevel());
        offerDiamonds(session, a);
        set(session, alice, "tokens", "101");
        set(session, bob, "tickets", "3");
        set(session, alice, "experience", "5");

        ready(session);
        assertTrue(session.finished());
        assertEquals(List.of("tokens:settle", "tickets:settle"), calls);
        assertEquals(List.of(10, 10), levels);
        assertEquals(tokens.trade, tickets.trade);
        assertEquals(List.of(new TradeCurrency.Payment(id(alice), id(bob), new BigDecimal("101"))), tokens.payments);
        assertEquals(List.of(new TradeCurrency.Payment(id(bob), id(alice), new BigDecimal("3"))), tickets.payments);
        assertBalances(tokens, "899", "1101");
        assertBalances(tickets, "1003", "997");
        assertEquals(5, alice.getLevel());
        assertEquals(5, bob.getLevel());
        await(() -> bob.getInventory().contains(Material.DIAMOND, 5));
        assertFalse(alice.getInventory().contains(Material.DIAMOND));
        assertFalse(logged(Level.SEVERE));
    }

    @Test
    void refusalRevertsSettledCurrenciesAndKeepsTheTradeOpen() {
        TradeSession session = open();
        TradeSide a = session.sideOf(alice);
        alice.setLevel(10);
        offerDiamonds(session, a);
        set(session, alice, "tokens", "50");
        set(session, bob, "tickets", "2");
        set(session, alice, "experience", "5");
        tickets.settles = false;

        ready(session);
        assertEquals(List.of("tokens:settle", "tickets:settle", "tokens:revert"), calls);
        assertFalse(session.finished());
        assertFalse(a.ready);
        assertBalances(tokens, "1000", "1000");
        assertBalances(tickets, "1000", "1000");
        assertEquals(10, alice.getLevel());
        assertNotNull(a.items[0]);
        assertFalse(logged(Level.SEVERE));
    }

    @Test
    void unknownOutcomeStopsTheTradeWithoutRetry() {
        TradeSession session = open();
        TradeSide a = session.sideOf(alice);
        offerDiamonds(session, a);
        set(session, alice, "tokens", "10");
        set(session, bob, "tickets", "1");
        tokens.settleFailure = new IllegalStateException("database is gone");

        ready(session);
        assertTrue(session.finished());
        assertEquals(List.of("tokens:settle"), calls);
        assertBalances(tickets, "1000", "1000");
        assertTrue(alice.getInventory().contains(Material.DIAMOND, 5));
        assertTrue(logged(Level.SEVERE, tokens.trade.toString(), "tokens"));
    }

    @Test
    void failedRevertStopsTheTradeWithoutClaimingTheBalancesAreRestored() {
        TradeSession session = open();
        TradeSide a = session.sideOf(alice);
        offerDiamonds(session, a);
        set(session, alice, "tokens", "10");
        set(session, bob, "tickets", "1");
        tickets.settles = false;
        tokens.revertFailure = new IllegalStateException("database is gone");
        drainMessages();

        ready(session);
        assertTrue(session.finished());
        assertEquals(List.of("tokens:settle", "tickets:settle", "tokens:revert"), calls);
        // The settled tokens could not be taken back: logged for staff, not hidden
        assertBalances(tokens, "990", "1010");
        assertTrue(alice.getInventory().contains(Material.DIAMOND, 5));
        assertTrue(logged(Level.SEVERE, tokens.trade.toString(), "tokens"));
        List<String> messages = drainMessages();
        assertTrue(messages.stream().anyMatch(message -> message.contains("could not be confirmed")), messages.toString());
        assertTrue(messages.stream().noneMatch(message -> message.contains("restored")), messages.toString());
    }

    @Test
    void laterRefusedPayoutUndoesEarlierPayoutsAndWithdrawals() {
        Points points = new Points();
        points.refusedDeposits = Set.of(2);
        TradeSide a = side(alice, "tokens", "5", "points", "7");
        TradeSide b = side(bob, "points", "3");

        Settlement.Failure failure = Settlement.run(plugin.getLogger(), List.of(currency("tokens"), points), a, b);
        // The payout of 7 to bob succeeded, the payout of 3 to alice was refused
        assertEquals(new Settlement.Failure("trade.failed.deposit", a, false), failure);
        assertEquals(List.of("tokens:settle", "tokens:revert"), calls);
        assertBalances(tokens, "1000", "1000");
        assertEquals(1000, points.of(alice));
        assertEquals(1000, points.of(bob));
        assertFalse(logged(Level.SEVERE));
    }

    @Test
    void builtInExceptionStopsAfterCompensating() {
        Points points = new Points();
        points.withdrawThrowsFor = "bob";
        TradeSide a = side(alice, "tokens", "5", "points", "7");
        TradeSide b = side(bob, "points", "3");

        Settlement.Failure failure = Settlement.run(plugin.getLogger(), List.of(currency("tokens"), points), a, b);
        assertEquals(new Settlement.Failure("trade.cancelled.error", null, true), failure);
        assertBalances(tokens, "1000", "1000");
        assertEquals(1000, points.of(alice));
        assertEquals(1000, points.of(bob));
        assertTrue(logged(Level.SEVERE, tokens.trade.toString(), "points"));
    }

    @Test
    void failedBuiltInUndoIsUnknown() {
        Points points = new Points();
        points.refusedDeposits = Set.of(1, 2);
        TradeSide a = side(alice, "points", "7");
        TradeSide b = side(bob);

        Settlement.Failure failure = Settlement.run(plugin.getLogger(), List.of(points), a, b);
        assertEquals(new Settlement.Failure("trade.cancelled.error", null, true), failure);
        // Neither the payout nor its undo went through, staff are told which currency to check
        assertEquals(993, points.of(alice));
        assertEquals(1000, points.of(bob));
        assertTrue(logged(Level.SEVERE, "points"));
    }

    private TradeSide side(Player player, String... amounts) {
        TradeSide side = new TradeSide(player);
        for (int i = 0; i < amounts.length; i += 2) side.currencies.put(amounts[i], new BigDecimal(amounts[i + 1]));
        return side;
    }

    private void assertBalances(FakeCurrency currency, String alices, String bobs) {
        assertEquals(new BigDecimal(alices), currency.balanceOf(id(alice)), currency.id() + " of alice");
        assertEquals(new BigDecimal(bobs), currency.balanceOf(id(bob)), currency.id() + " of bob");
    }

    // A built-in currency with balances in memory, starting at 1000
    private static final class Points implements BuiltInCurrency {
        private final Map<String, Integer> balances = new HashMap<>();
        private int deposits;
        // Numbers of the deposit calls that are refused, counted from 1
        Set<Integer> refusedDeposits = Set.of();
        String withdrawThrowsFor;

        int of(Player player) { return balances.getOrDefault(player.getName(), 1000); }

        @Override public String id() { return "points"; }
        @Override public Material icon() { return Material.GOLD_NUGGET; }
        @Override public int scale() { return 0; }
        @Override public BigDecimal balance(Player player) { return BigDecimal.valueOf(of(player)); }
        @Override public String format(BigDecimal amount) { return amount.toPlainString(); }

        @Override
        public boolean withdraw(Player player, BigDecimal amount) {
            if (player.getName().equals(withdrawThrowsFor)) throw new IllegalStateException("economy is gone");
            if (of(player) < amount.intValueExact()) return false;
            balances.put(player.getName(), of(player) - amount.intValueExact());
            return true;
        }

        @Override
        public boolean deposit(Player player, BigDecimal amount) {
            if (refusedDeposits.contains(++deposits)) return false;
            balances.put(player.getName(), of(player) + amount.intValueExact());
            return true;
        }
    }
}
