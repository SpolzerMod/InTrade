package me.spolzer.intrade.api;

import java.util.List;
import java.util.Map;
import org.bukkit.inventory.ItemStack;

/** Items and currencies offered by one side. Items are copies. */
public record TradeOffer(List<ItemStack> items, Map<String, Double> currencies) {

    public TradeOffer {
        items = items.stream().map(ItemStack::clone).toList();
        currencies = Map.copyOf(currencies);
    }

    public double currency(String id) {
        return currencies.getOrDefault(id, 0.0);
    }
}
