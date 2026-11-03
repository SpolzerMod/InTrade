package me.spolzer.intrade.currency;

import java.math.BigDecimal;
import org.bukkit.entity.Player;

/** Currency of a plugin that InTrade supports itself. Transfers run synchronously on the main thread. */
public interface BuiltInCurrency extends Currency {
    boolean withdraw(Player player, BigDecimal amount);
    boolean deposit(Player player, BigDecimal amount);
}
