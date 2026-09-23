package me.spolzer.intrade.api.event;

import me.spolzer.intrade.api.TradeOffer;
import org.bukkit.entity.Player;
import org.bukkit.event.Event;
import org.bukkit.event.HandlerList;
import org.jetbrains.annotations.NotNull;

public final class TradeCompleteEvent extends Event {
    private static final HandlerList HANDLERS = new HandlerList();

    private final Player first;
    private final Player second;
    private final TradeOffer firstOffer;
    private final TradeOffer secondOffer;

    public TradeCompleteEvent(Player first, Player second, TradeOffer firstOffer, TradeOffer secondOffer) {
        this.first = first;
        this.second = second;
        this.firstOffer = firstOffer;
        this.secondOffer = secondOffer;
    }

    public Player first() { return first; }
    public Player second() { return second; }

    public TradeOffer gave(Player player) { return isFirst(player) ? firstOffer : secondOffer; }
    public TradeOffer got(Player player) { return isFirst(player) ? secondOffer : firstOffer; }
    public Player partnerOf(Player player) { return isFirst(player) ? second : first; }

    private boolean isFirst(Player player) {
        if (player.getUniqueId().equals(first.getUniqueId())) return true;
        if (player.getUniqueId().equals(second.getUniqueId())) return false;
        throw new IllegalArgumentException(player.getName() + " is not part of this trade");
    }

    @Override public @NotNull HandlerList getHandlers() { return HANDLERS; }
    public static HandlerList getHandlerList() { return HANDLERS; }
}
