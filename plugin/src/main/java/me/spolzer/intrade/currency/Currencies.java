package me.spolzer.intrade.currency;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.logging.Level;
import java.util.logging.Logger;
import java.util.regex.Pattern;
import me.spolzer.intrade.api.currency.TradeCurrency;
import me.spolzer.intrade.config.Settings;
import org.bukkit.Bukkit;
import org.bukkit.Material;
import org.bukkit.permissions.Permissible;
import org.bukkit.permissions.Permission;
import org.bukkit.permissions.PermissionDefault;
import org.bukkit.plugin.PluginManager;
import org.bukkit.plugin.RegisteredServiceProvider;

public final class Currencies {
    // Plain digits only: BigDecimal alone would also accept exponents like 1e999999999
    private static final Pattern NUMBER = Pattern.compile("\\d{1,18}(\\.\\d*)?|\\.\\d{1,18}");
    private static final Pattern ID = Pattern.compile("[a-z0-9_-]{1,32}");
    private static final BigDecimal HUNDRED = BigDecimal.valueOf(100);
    // Number of currency buttons in the trade window
    private static final int SHOWN = 3;
    // Amounts are typed with at most 18 digits, see NUMBER
    private static final int MAX_SCALE = 18;

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
        loaded.addAll(external(settings, logger));
        List<String> ids = loaded.stream().map(Currency::id).toList();
        // Only the currencies that have a button in the trade window can be traded
        active = List.copyOf(loaded.subList(0, Math.min(SHOWN, loaded.size())));
        logger.info("Currencies: " + (ids.isEmpty() ? "none" : String.join(", ", ids.subList(0, active.size()))));
        if (ids.size() > SHOWN) {
            logger.warning("The trade window has room for " + SHOWN + " currencies, not used: "
                    + String.join(", ", ids.subList(SHOWN, ids.size())) + ". Disable the currencies you do not need");
        }
    }

    // Currencies registered by other plugins, in the order of the config
    private static List<Currency> external(Settings settings, Logger logger) {
        List<Currency> found = new ArrayList<>();
        Set<String> seen = new HashSet<>(Settings.BUILT_IN_CURRENCIES);
        for (RegisteredServiceProvider<TradeCurrency> registration : Bukkit.getServicesManager().getRegistrations(TradeCurrency.class)) {
            TradeCurrency provider = registration.getProvider();
            String owner = registration.getPlugin().getName();
            String id;
            Material icon;
            int scale;
            try {
                id = provider.id();
                icon = provider.icon();
                scale = provider.scale();
            } catch (RuntimeException e) {
                logger.log(Level.WARNING, "Could not read a currency of " + owner, e);
                continue;
            }
            if (id == null || !ID.matcher(id).matches()) {
                logger.warning(owner + " registered a currency with the invalid id '" + id + "', expected " + ID.pattern());
                continue;
            }
            if (!seen.add(id)) {
                logger.warning(owner + " registered the currency '" + id + "', which already exists. Ignored");
                continue;
            }
            if (!settings.currencyEnabled(id)) continue;
            if (icon == null || !icon.isItem() || icon.isAir()) {
                logger.warning(owner + " registered the currency '" + id + "' with the icon " + icon + ", which is not an item. Ignored");
                continue;
            }
            if (scale < 0 || scale > MAX_SCALE) {
                logger.warning(owner + " registered the currency '" + id + "' with the scale " + scale
                        + ", expected 0 to " + MAX_SCALE + ". Ignored");
                continue;
            }
            permission(id);
            found.add(new ExternalCurrency(provider, id, owner, icon, scale));
        }
        List<String> order = List.copyOf(settings.currencies());
        found.sort(Comparator.comparingInt(currency -> order.indexOf(currency.id())));
        return found;
    }

    // Registered like the built-in currency permissions, so intrade.currency.* and intrade.player grant it
    private static void permission(String id) {
        PluginManager manager = Bukkit.getPluginManager();
        String name = "intrade.currency." + id;
        Permission parent = manager.getPermission("intrade.currency.*");
        PermissionDefault fallback = parent != null ? parent.getDefault() : PermissionDefault.TRUE;
        if (manager.getPermission(name) == null) manager.addPermission(new Permission(name, "Trade " + id + ".", fallback));
        if (parent != null && !parent.getChildren().containsKey(name)) {
            parent.getChildren().put(name, true);
            parent.recalculatePermissibles();
        }
    }

    /**
     * Part of an amount that is taken as tax. Rounded up to the currency scale, so splitting an amount into many
     * small trades does not avoid the tax.
     */
    public static BigDecimal tax(BigDecimal amount, BigDecimal percent, int scale) {
        if (percent.signum() <= 0 || amount.signum() <= 0) return BigDecimal.ZERO.setScale(scale);
        return amount.multiply(percent).divide(HUNDRED).setScale(scale, RoundingMode.UP).min(amount);
    }

    public static BigDecimal tax(Settings settings, Currency currency, BigDecimal amount) {
        return tax(amount, settings.taxPercent(currency.id()), currency.scale());
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
     * The active currency with the id of {@code currency}, or null if it is no longer active or another plugin's
     * currency was replaced by a new provider since {@code currency} was read.
     */
    public Currency current(Currency currency) {
        Currency now = byId(currency.id());
        if (now instanceof ExternalCurrency external && !external.sameProvider(currency)) return null;
        return now;
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
