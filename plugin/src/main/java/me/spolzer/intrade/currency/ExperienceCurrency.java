package me.spolzer.intrade.currency;

import java.text.NumberFormat;
import java.util.Locale;
import org.bukkit.Material;
import org.bukkit.entity.Player;

final class ExperienceCurrency implements Currency {
    private final NumberFormat format;

    ExperienceCurrency(Locale locale) {
        format = NumberFormat.getIntegerInstance(locale);
    }

    @Override public String id() { return "experience"; }
    @Override public Material icon() { return Material.EXPERIENCE_BOTTLE; }
    @Override public boolean fractional() { return false; }
    @Override public double balance(Player player) { return player.getLevel(); }

    @Override
    public boolean withdraw(Player player, double amount) {
        int levels = (int) amount;
        if (player.getLevel() < levels) return false;
        player.setLevel(player.getLevel() - levels);
        return true;
    }

    @Override
    public boolean deposit(Player player, double amount) {
        player.giveExpLevels((int) amount);
        return true;
    }
    @Override public String format(double amount) { return format.format((long) amount); }
}
