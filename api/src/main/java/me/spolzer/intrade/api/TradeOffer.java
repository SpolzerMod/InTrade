package me.spolzer.intrade.api;

import java.math.BigDecimal;
import java.util.List;
import java.util.Map;
import org.bukkit.inventory.ItemStack;

/**
 * What one side gave in a trade.
 *
 * @param items copies of the offered items
 * @param currencies offered amounts by currency id, only currencies with a positive amount
 */
public record TradeOffer(List<ItemStack> items, Map<String, BigDecimal> currencies) {

    public TradeOffer {
        items = items.stream().map(ItemStack::clone).toList();
        currencies = Map.copyOf(currencies);
    }

    /** Offered amount of the currency, zero if it was not offered. */
    public BigDecimal currency(String id) {
        return currencies.getOrDefault(id, BigDecimal.ZERO);
    }
}
