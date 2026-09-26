package me.spolzer.intrade.currency;

import java.math.BigDecimal;
import org.bukkit.Material;
import org.bukkit.entity.Player;

public interface Currency {
    String id();
    Material icon();

    /** Number of digits after the decimal point that the currency supports. */
    int scale();

    BigDecimal balance(Player player);
    boolean withdraw(Player player, BigDecimal amount);
    boolean deposit(Player player, BigDecimal amount);
    String format(BigDecimal amount);
}
