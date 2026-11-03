package me.spolzer.intrade.api.currency;

import java.math.BigDecimal;
import java.util.List;
import java.util.Objects;
import java.util.UUID;
import org.bukkit.Material;
import org.bukkit.entity.Player;

/**
 * A currency provided by another plugin.
 *
 * <p>Register an implementation in the Bukkit {@link org.bukkit.plugin.ServicesManager} with the service class
 * {@code TradeCurrency}. Several plugins can register currencies. InTrade picks them up when it is enabled, on
 * {@code /trade reload} and whenever a provider is registered or unregistered. A registered currency is used only
 * when {@code currencies.<id>.enabled} is {@code true} in the InTrade config. The ids {@code money},
 * {@code experience} and {@code playerpoints} belong to the built-in currencies and are ignored.
 *
 * <p>Players need the permission {@code intrade.currency.<id>} (granted by {@code intrade.player}). The name shown
 * to players is the language key {@code currency.<id>}, the id itself if the key is missing.
 *
 * <p>All methods are called on the main server thread. {@link #id()}, {@link #icon()} and {@link #scale()} are read
 * once when the currency is loaded; a currency with an invalid value is ignored and a warning is logged.
 *
 * <p>If {@link #balance(Player)} or {@link #format(BigDecimal)} throws or returns null, the trade that called it is
 * cancelled, its items are returned and the error is logged. Only the currencies that fit in the trade window (three, in the
 * order of the config) are used. When a currency is disabled, displaced, unregistered or replaced by a new provider,
 * its amounts are removed from open trades and both players confirm again; an amount the player is still typing is
 * discarded without calling the old provider.
 *
 * <p>Known limitation: as with the built-in currencies, the currencies are settled before the items are swapped. If
 * the server stops between the two steps, the payments stay applied while the items go back to their owners.
 */
public interface TradeCurrency {

    /** Lower case id matching {@code [a-z0-9_-]{1,32}}. Currencies with another id are ignored. */
    String id();

    /** Icon of the currency in the trade window. Must be an item, not null or air. */
    Material icon();

    /** Number of digits after the decimal point, 0 to 18. Amounts are cut to this scale. */
    int scale();

    /**
     * The amount the player can offer right now. Called often while the trade window is shown, so it must be cheap
     * and must never block, for example by reading a cached balance. Never null.
     */
    BigDecimal balance(Player player);

    /** Plain text representation of an amount, for example {@code 1,500 tokens}. Never null. */
    String format(BigDecimal amount);

    /**
     * Applies all payments of one completed trade in this currency, all of them or none.
     *
     * <p>Returns {@code true} when every payment was applied and {@code false} when none was, for example because a
     * balance changed. An exception means the outcome is unknown: InTrade then cancels the trade, returns the items
     * and logs the trade id, so staff can check the balances.
     *
     * <p>Called while the trade completes, like the transfers of the built-in currencies. It may block briefly,
     * for example for a local database transaction, but must never wait for a network call.
     *
     * @param trade id of this completion attempt, never settled twice
     * @param payments the payments of both players in this currency, never empty
     */
    boolean settle(UUID trade, List<Payment> payments);

    /**
     * Undoes a successful {@link #settle(UUID, List)} of the trade, because a currency settled after this one failed.
     * Calling it again for the same trade must have no further effect. An exception means the payments were not undone.
     */
    void revert(UUID trade);

    /**
     * One payment of a trade: {@code from} pays {@code amount}, {@code to} receives {@code amount - tax}. The tax
     * ({@code currencies.<id>.tax-percent}, 0 by default) is removed from the economy. All amounts use the currency
     * scale.
     */
    record Payment(UUID from, UUID to, BigDecimal amount, BigDecimal tax) {

        public Payment {
            Objects.requireNonNull(from, "from");
            Objects.requireNonNull(to, "to");
            if (amount.signum() <= 0) throw new IllegalArgumentException("amount must be positive");
            if (tax.signum() < 0 || tax.compareTo(amount) > 0) throw new IllegalArgumentException("tax must be between 0 and amount");
        }

        /** What {@code to} receives. */
        public BigDecimal received() {
            return amount.subtract(tax);
        }
    }
}
