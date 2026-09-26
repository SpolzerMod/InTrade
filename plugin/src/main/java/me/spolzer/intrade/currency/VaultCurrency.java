package me.spolzer.intrade.currency;

import java.math.BigDecimal;
import java.math.RoundingMode;
import net.milkbowl.vault.economy.Economy;
import org.bukkit.Bukkit;
import org.bukkit.Material;
import org.bukkit.entity.Player;
import org.bukkit.plugin.RegisteredServiceProvider;

// Vault works with double, so amounts are converted only at this boundary
final class VaultCurrency implements Currency {
    private final Economy economy;
    private final int scale;

    private VaultCurrency(Economy economy) {
        this.economy = economy;
        // -1 means the economy plugin does not say, two digits cover every common setup
        int digits = economy.fractionalDigits();
        this.scale = digits < 0 ? 2 : digits;
    }

    static VaultCurrency create() {
        RegisteredServiceProvider<Economy> provider = Bukkit.getServicesManager().getRegistration(Economy.class);
        return provider == null ? null : new VaultCurrency(provider.getProvider());
    }

    @Override public String id() { return "money"; }
    @Override public Material icon() { return Material.GOLD_INGOT; }
    @Override public int scale() { return scale; }

    @Override
    public BigDecimal balance(Player player) {
        return BigDecimal.valueOf(economy.getBalance(player)).setScale(scale, RoundingMode.DOWN);
    }

    @Override
    public boolean withdraw(Player player, BigDecimal amount) {
        double value = amount.doubleValue();
        return economy.has(player, value) && economy.withdrawPlayer(player, value).transactionSuccess();
    }

    @Override
    public boolean deposit(Player player, BigDecimal amount) {
        return economy.depositPlayer(player, amount.doubleValue()).transactionSuccess();
    }

    @Override public String format(BigDecimal amount) { return economy.format(amount.doubleValue()); }
}
