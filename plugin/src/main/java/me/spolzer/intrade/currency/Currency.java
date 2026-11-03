package me.spolzer.intrade.currency;

import java.math.BigDecimal;
import org.bukkit.Material;
import org.bukkit.entity.Player;

/** A currency in the trade window, either {@link BuiltInCurrency} or {@link ExternalCurrency}. */
public interface Currency {
    String id();
    Material icon();

    /** Number of digits after the decimal point that the currency supports. */
    int scale();

    BigDecimal balance(Player player);
    String format(BigDecimal amount);
}
