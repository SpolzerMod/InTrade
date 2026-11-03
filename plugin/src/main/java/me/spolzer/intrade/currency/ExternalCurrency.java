package me.spolzer.intrade.currency;

import java.math.BigDecimal;
import me.spolzer.intrade.api.currency.TradeCurrency;
import org.bukkit.Material;
import org.bukkit.entity.Player;

/**
 * Currency registered by another plugin through the API. The icon and scale are read once when the currency is
 * loaded. A failing {@code balance} or {@code format} call throws a {@link CurrencyException}.
 */
public record ExternalCurrency(TradeCurrency provider, String id, String owner, Material icon, int scale) implements Currency {

    @Override
    public BigDecimal balance(Player player) {
        BigDecimal balance;
        try {
            balance = provider.balance(player);
        } catch (RuntimeException e) {
            throw new CurrencyException(this, "balance", e);
        }
        if (balance == null) throw new CurrencyException(this, "balance", null);
        return balance;
    }

    @Override
    public String format(BigDecimal amount) {
        String text;
        try {
            text = provider.format(amount);
        } catch (RuntimeException e) {
            throw new CurrencyException(this, "format", e);
        }
        if (text == null) throw new CurrencyException(this, "format", null);
        return text;
    }

    /** Whether both are the same currency of the same provider instance. */
    boolean sameProvider(Currency other) {
        return other instanceof ExternalCurrency external && external.provider == provider && external.id.equals(id);
    }
}
