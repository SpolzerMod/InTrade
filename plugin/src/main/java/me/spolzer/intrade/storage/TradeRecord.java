package me.spolzer.intrade.storage;

import java.math.BigDecimal;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.bukkit.inventory.ItemStack;

// Side A gave aItems and aCurrencies, side B gave bItems and bCurrencies
public record TradeRecord(long time, UUID aId, String aName, UUID bId, String bName,
                          List<ItemStack> aItems, List<ItemStack> bItems,
                          Map<String, BigDecimal> aCurrencies, Map<String, BigDecimal> bCurrencies) {

    public boolean isA(UUID player) { return aId.equals(player); }
    public UUID partnerOf(UUID player) { return isA(player) ? bId : aId; }
    public String partnerNameOf(UUID player) { return isA(player) ? bName : aName; }
    public List<ItemStack> gaveItems(UUID player) { return isA(player) ? aItems : bItems; }
    public List<ItemStack> gotItems(UUID player) { return isA(player) ? bItems : aItems; }
    public Map<String, BigDecimal> gaveCurrencies(UUID player) { return isA(player) ? aCurrencies : bCurrencies; }
    public Map<String, BigDecimal> gotCurrencies(UUID player) { return isA(player) ? bCurrencies : aCurrencies; }
}
