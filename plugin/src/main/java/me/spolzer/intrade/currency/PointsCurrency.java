package me.spolzer.intrade.currency;

import java.math.BigDecimal;
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
    @Override public int scale() { return 0; }
    @Override public BigDecimal balance(Player player) { return BigDecimal.valueOf(api.look(player.getUniqueId())); }

    @Override
    public boolean withdraw(Player player, BigDecimal amount) {
        int points = amount.intValueExact();
        return api.look(player.getUniqueId()) >= points && api.take(player.getUniqueId(), points);
    }

    @Override
    public boolean deposit(Player player, BigDecimal amount) {
        return api.give(player.getUniqueId(), amount.intValueExact());
    }

    @Override public String format(BigDecimal amount) { return format.format(amount); }
}
