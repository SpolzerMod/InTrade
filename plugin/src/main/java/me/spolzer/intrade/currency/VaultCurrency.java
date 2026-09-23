package me.spolzer.intrade.currency;

import net.milkbowl.vault.economy.Economy;
import org.bukkit.Bukkit;
import org.bukkit.Material;
import org.bukkit.entity.Player;
import org.bukkit.plugin.RegisteredServiceProvider;

final class VaultCurrency implements Currency {
    private final Economy economy;

    private VaultCurrency(Economy economy) {
        this.economy = economy;
    }

    static VaultCurrency create() {
        RegisteredServiceProvider<Economy> provider = Bukkit.getServicesManager().getRegistration(Economy.class);
        return provider == null ? null : new VaultCurrency(provider.getProvider());
    }

    @Override public String id() { return "money"; }
    @Override public Material icon() { return Material.GOLD_INGOT; }
    @Override public boolean fractional() { return economy.fractionalDigits() != 0; }
    @Override public double balance(Player player) { return economy.getBalance(player); }

    @Override
    public boolean withdraw(Player player, double amount) {
        return economy.has(player, amount) && economy.withdrawPlayer(player, amount).transactionSuccess();
    }

    @Override
    public boolean deposit(Player player, double amount) {
        return economy.depositPlayer(player, amount).transactionSuccess();
    }
    @Override public String format(double amount) { return economy.format(amount); }
}
