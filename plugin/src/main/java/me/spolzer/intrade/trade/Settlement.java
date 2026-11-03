package me.spolzer.intrade.trade;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.function.BooleanSupplier;
import java.util.logging.Level;
import java.util.logging.Logger;
import me.spolzer.intrade.api.currency.TradeCurrency;
import me.spolzer.intrade.api.currency.TradeCurrency.Payment;
import me.spolzer.intrade.currency.BuiltInCurrency;
import me.spolzer.intrade.currency.Currency;
import me.spolzer.intrade.currency.ExternalCurrency;

/**
 * Moves the currencies of a confirmed trade in one main thread step. The currencies of other plugins are settled
 * first, one call each in the order of the config, then the built-in currencies: everything is withdrawn before
 * anything is paid out. If a step fails, the steps done so far are undone in reverse order. If the outcome of a step
 * or of an undo is unknown, the balances may be wrong: the failure is then {@code unknown} and logged with the trade
 * id.
 */
final class Settlement {
    private final UUID trade = UUID.randomUUID();
    private final Logger logger;
    private final TradeSide a;
    private final TradeSide b;
    private final List<Step> done = new ArrayList<>();

    /** Why the currencies were not moved. Without {@code unknown}, every balance is as before. */
    record Failure(String key, TradeSide cause, boolean unknown) {
    }

    private record Charge(Currency currency, List<Payment> payments) {
    }

    // The undo of a step, which returns false or throws if it could not be undone
    private record Step(Currency currency, String what, BooleanSupplier undo) {
    }

    private Settlement(Logger logger, TradeSide a, TradeSide b) {
        this.logger = logger;
        this.a = a;
        this.b = b;
    }

    /** Moves the offered amounts of all currencies, or none of them. Returns null when everything was moved. */
    static Failure run(Logger logger, List<Currency> currencies, TradeSide a, TradeSide b) {
        return new Settlement(logger, a, b).run(currencies);
    }

    private Failure run(List<Currency> currencies) {
        List<Charge> external = new ArrayList<>();
        List<Charge> builtIn = new ArrayList<>();
        for (Currency currency : currencies) {
            List<Payment> payments = new ArrayList<>();
            for (TradeSide side : List.of(a, b)) {
                BigDecimal amount = side.currency(currency.id());
                if (amount.signum() <= 0) continue;
                payments.add(new Payment(side.id(), other(side).id(), amount));
            }
            if (!payments.isEmpty()) {
                (currency instanceof ExternalCurrency ? external : builtIn).add(new Charge(currency, List.copyOf(payments)));
            }
        }

        for (Charge charge : external) {
            TradeCurrency provider = ((ExternalCurrency) charge.currency()).provider();
            boolean settled;
            try {
                settled = provider.settle(trade, charge.payments());
            } catch (Exception e) {
                return unknown(charge.currency(), "the settlement", e);
            }
            if (!settled) return refused(new Failure("trade.failed.settle", null, false));
            done.add(new Step(charge.currency(), "revert the settlement", () -> {
                provider.revert(trade);
                return true;
            }));
        }
        for (Charge charge : builtIn) {
            for (Payment payment : charge.payments()) {
                Failure failure = transfer(charge.currency(), side(payment.from()), payment.amount(), true);
                if (failure != null) return failure;
            }
        }
        for (Charge charge : builtIn) {
            for (Payment payment : charge.payments()) {
                Failure failure = transfer(charge.currency(), side(payment.to()), payment.amount(), false);
                if (failure != null) return failure;
            }
        }
        return null;
    }

    private Failure transfer(Currency currency, TradeSide side, BigDecimal amount, boolean withdraw) {
        BuiltInCurrency builtIn = (BuiltInCurrency) currency;
        String what = (withdraw ? "the withdrawal of " : "the deposit of ") + amount.toPlainString()
                + (withdraw ? " from " : " to ") + side.player.getName();
        boolean moved;
        try {
            moved = withdraw ? builtIn.withdraw(side.player, amount) : builtIn.deposit(side.player, amount);
        } catch (Exception e) {
            return unknown(currency, what, e);
        }
        if (!moved) return refused(new Failure(withdraw ? "trade.failed.not-enough" : "trade.failed.deposit", side, false));
        done.add(new Step(currency, "undo " + what,
                () -> withdraw ? builtIn.deposit(side.player, amount) : builtIn.withdraw(side.player, amount)));
        return null;
    }

    private Failure unknown(Currency currency, String what, Exception e) {
        logger.log(Level.SEVERE, "Trade " + trade + ": " + what + " in " + currency.id() + " failed", e);
        Set<String> concerned = new LinkedHashSet<>();
        concerned.add(currency.id());
        return compensate(concerned);
    }

    private Failure refused(Failure failure) {
        Failure broken = compensate(new LinkedHashSet<>());
        return broken != null ? broken : failure;
    }

    // Undoes the steps done so far in reverse order, then reports the currencies whose balances may be wrong
    private Failure compensate(Set<String> concerned) {
        for (int i = done.size() - 1; i >= 0; i--) {
            Step step = done.get(i);
            Exception error = null;
            boolean undone;
            try {
                undone = step.undo().getAsBoolean();
            } catch (Exception e) {
                undone = false;
                error = e;
            }
            if (!undone) {
                concerned.add(step.currency().id());
                logger.log(Level.SEVERE, "Trade " + trade + ": could not " + step.what() + " in " + step.currency().id(), error);
            }
        }
        done.clear();
        if (concerned.isEmpty()) return null;
        logger.severe("Trade " + trade + " between " + a.player.getName() + " and " + b.player.getName()
                + " is cancelled. The balances of " + String.join(", ", concerned)
                + " may be wrong and need to be checked manually");
        return new Failure("trade.cancelled.error", null, true);
    }

    private TradeSide other(TradeSide side) { return side == a ? b : a; }

    private TradeSide side(UUID id) { return a.id().equals(id) ? a : b; }
}
