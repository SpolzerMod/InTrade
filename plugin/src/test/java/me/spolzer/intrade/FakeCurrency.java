package me.spolzer.intrade;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import me.spolzer.intrade.api.currency.TradeCurrency;
import org.bukkit.Material;
import org.bukkit.entity.Player;

/** A currency of another plugin for the tests. Balances start at 1000; settle applies all payments or none. */
public final class FakeCurrency implements TradeCurrency {
    private static final BigDecimal START = BigDecimal.valueOf(1000);

    private final String id;
    private final List<String> log;
    private final Map<UUID, BigDecimal> balances = new HashMap<>();
    private final Map<UUID, List<Payment>> settled = new HashMap<>();
    public boolean settles = true;
    public RuntimeException settleFailure;
    public RuntimeException revertFailure;
    public UUID balanceFailsFor;
    public BigDecimal formatFailsFor;
    public Runnable onSettle = () -> {};
    public UUID trade;
    public List<Payment> payments = List.of();
    // Set once the provider is unregistered or replaced, every later call is counted
    public boolean retired;
    public int retiredCalls;

    public FakeCurrency(String id, List<String> log) {
        this.id = id;
        this.log = log;
    }

    public BigDecimal balanceOf(UUID player) { return balances.getOrDefault(player, START); }

    @Override public String id() { return id; }
    @Override public Material icon() { return Material.EMERALD; }
    @Override public int scale() { return 0; }

    @Override
    public BigDecimal balance(Player player) {
        called();
        if (player.getUniqueId().equals(balanceFailsFor)) throw new IllegalStateException("balance is gone");
        return balanceOf(player.getUniqueId());
    }

    @Override
    public String format(BigDecimal amount) {
        called();
        if (formatFailsFor != null && amount.compareTo(formatFailsFor) == 0) throw new IllegalStateException("format is gone");
        return amount.toPlainString() + " " + id;
    }

    @Override
    public boolean settle(UUID trade, List<Payment> payments) {
        called();
        log.add(id + ":settle");
        onSettle.run();
        this.trade = trade;
        this.payments = payments;
        if (settleFailure != null) throw settleFailure;
        if (!settles) return false;
        Map<UUID, BigDecimal> next = new HashMap<>(balances);
        for (Payment payment : payments) {
            BigDecimal from = next.getOrDefault(payment.from(), START).subtract(payment.amount());
            if (from.signum() < 0) return false;
            next.put(payment.from(), from);
            next.merge(payment.to(), START.add(payment.amount()), (old, ignored) -> old.add(payment.amount()));
        }
        balances.putAll(next);
        settled.put(trade, payments);
        return true;
    }

    @Override
    public void revert(UUID trade) {
        called();
        log.add(id + ":revert");
        if (revertFailure != null) throw revertFailure;
        List<Payment> done = settled.remove(trade);
        if (done == null) return;
        for (Payment payment : done) {
            balances.put(payment.from(), balanceOf(payment.from()).add(payment.amount()));
            balances.put(payment.to(), balanceOf(payment.to()).subtract(payment.amount()));
        }
    }

    private void called() {
        if (retired) retiredCalls++;
    }
}
