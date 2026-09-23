package me.spolzer.intrade.currency;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.logging.Logger;
import me.spolzer.intrade.config.Settings;
import org.bukkit.Bukkit;
import org.bukkit.permissions.Permissible;

public final class Currencies {
    private final List<Currency> active = new ArrayList<>();

    public void load(Settings settings, Locale locale, Logger logger) {
        active.clear();
        if (settings.currencyEnabled("money") && Bukkit.getPluginManager().isPluginEnabled("Vault")) {
            Currency money = VaultCurrency.create();
            if (money != null) active.add(money);
            else logger.warning("Vault is installed, but no economy plugin is registered. Money trading is disabled");
        }
        if (settings.currencyEnabled("experience")) active.add(new ExperienceCurrency(locale));
        if (settings.currencyEnabled("playerpoints") && Bukkit.getPluginManager().isPluginEnabled("PlayerPoints")) {
            active.add(new PointsCurrency(locale));
        }
        List<String> ids = active.stream().map(Currency::id).toList();
        logger.info("Currencies: " + (ids.isEmpty() ? "none" : String.join(", ", ids)));
    }

    public List<Currency> active() { return active; }

    public boolean allowed(Permissible who, Currency currency) {
        return who.hasPermission("intrade.currency." + currency.id());
    }

    public Currency byId(String id) {
        for (Currency currency : active) {
            if (currency.id().equals(id)) return currency;
        }
        return null;
    }

    // Parses 1500, 2.5k, 1m. Returns -1 for invalid input
    public static double parse(String text, boolean fractional) {
        String value = text.trim().toLowerCase(Locale.ROOT).replace(",", ".").replace(" ", "").replace("_", "");
        if (value.isEmpty()) return -1;
        long multiplier = switch (value.charAt(value.length() - 1)) {
            case 'k', 'к' -> 1_000L;
            case 'm', 'м' -> 1_000_000L;
            case 'b', 'б' -> 1_000_000_000L;
            default -> 1;
        };
        if (multiplier != 1) value = value.substring(0, value.length() - 1);
        try {
            BigDecimal amount = new BigDecimal(value).multiply(BigDecimal.valueOf(multiplier));
            amount = amount.setScale(fractional ? 2 : 0, RoundingMode.DOWN);
            return amount.signum() < 0 ? -1 : amount.doubleValue();
        } catch (NumberFormatException e) {
            return -1;
        }
    }
}
