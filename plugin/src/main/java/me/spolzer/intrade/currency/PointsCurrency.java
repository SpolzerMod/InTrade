package me.spolzer.intrade.currency;

import java.text.NumberFormat;
import java.util.Locale;
import org.black_ixx.playerpoints.PlayerPoints;
import org.black_ixx.playerpoints.PlayerPointsAPI;
import org.bukkit.Material;
import org.bukkit.entity.Player;

final class PointsCurrency implements Currency {
    private final PlayerPointsAPI api = PlayerPoints.getInstance().getAPI();
    private final NumberFormat format;

    PointsCurrency(Locale locale) {
        format = NumberFormat.getIntegerInstance(locale);
    }

    @Override public String id() { return "playerpoints"; }
    @Override public Material icon() { return Material.AMETHYST_SHARD; }
    @Override public boolean fractional() { return false; }
    @Override public double balance(Player player) { return api.look(player.getUniqueId()); }

    @Override
    public boolean withdraw(Player player, double amount) {
        int points = (int) amount;
        return api.look(player.getUniqueId()) >= points && api.take(player.getUniqueId(), points);
    }

    @Override public boolean deposit(Player player, double amount) { return api.give(player.getUniqueId(), (int) amount); }
    @Override public String format(double amount) { return format.format((long) amount); }
}
