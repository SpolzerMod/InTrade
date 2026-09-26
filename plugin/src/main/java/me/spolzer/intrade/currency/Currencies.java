package me.spolzer.intrade.currency;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.logging.Logger;
import java.util.regex.Pattern;
import me.spolzer.intrade.config.Settings;
import org.bukkit.Bukkit;
import org.bukkit.permissions.Permissible;

public final class Currencies {
    // Plain digits only: BigDecimal alone would also accept exponents like 1e999999999
    private static final Pattern NUMBER = Pattern.compile("\\d{1,18}(\\.\\d*)?|\\.\\d{1,18}");

    // Replaced as a whole on reload, the API may read it from other threads
    private volatile List<Currency> active = List.of();

    public void load(Settings settings, Locale locale, Logger logger) {
        List<Currency> loaded = new ArrayList<>();
        if (settings.currencyEnabled("money") && Bukkit.getPluginManager().isPluginEnabled("Vault")) {
            Currency money = VaultCurrency.create();
            if (money != null) loaded.add(money);
            else logger.warning("Vault is installed, but no economy plugin is registered. Money trading is disabled");
        }
        if (settings.currencyEnabled("experience")) loaded.add(new ExperienceCurrency(locale));
        if (settings.currencyEnabled("playerpoints") && Bukkit.getPluginManager().isPluginEnabled("PlayerPoints")) {
            loaded.add(new PointsCurrency(locale));
        }
        active = List.copyOf(loaded);
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

    /**
     * Parses player input such as {@code 1500}, {@code 2.5k} or {@code 1m}. Extra digits are cut to the
     * currency scale.
     *
     * @return the amount, zero for empty input, or null if the input is not a non-negative number
     */
    public static BigDecimal parse(String text, int scale) {
        String value = text.trim().toLowerCase(Locale.ROOT).replace(",", ".").replace(" ", "").replace("_", "");
        if (value.isEmpty()) return BigDecimal.ZERO;
        int shift = switch (value.charAt(value.length() - 1)) {
            case 'k', 'к' -> 3;
            case 'm', 'м' -> 6;
            case 'b', 'б' -> 9;
            default -> 0;
        };
        if (shift != 0) value = value.substring(0, value.length() - 1);
        if (!NUMBER.matcher(value).matches()) return null;
        return new BigDecimal(value).movePointRight(shift).setScale(scale, RoundingMode.DOWN);
    }
}
