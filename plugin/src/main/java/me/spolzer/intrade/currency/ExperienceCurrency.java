package me.spolzer.intrade.currency;

import java.math.BigDecimal;
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
    @Override public int scale() { return 0; }
    @Override public BigDecimal balance(Player player) { return BigDecimal.valueOf(player.getLevel()); }

    @Override
    public boolean withdraw(Player player, BigDecimal amount) {
        int levels = amount.intValueExact();
        if (player.getLevel() < levels) return false;
        player.setLevel(player.getLevel() - levels);
        return true;
    }

    @Override
    public boolean deposit(Player player, BigDecimal amount) {
        player.giveExpLevels(amount.intValueExact());
        return true;
    }

    @Override public String format(BigDecimal amount) { return format.format(amount); }
}
