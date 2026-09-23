package me.spolzer.intrade.currency;

import org.bukkit.Material;
import org.bukkit.entity.Player;

public interface Currency {
    String id();
    Material icon();
    boolean fractional();
    double balance(Player player);
    boolean withdraw(Player player, double amount);
    boolean deposit(Player player, double amount);
    String format(double amount);
}
