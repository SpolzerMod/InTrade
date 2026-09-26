package me.spolzer.intrade.api.event;

import org.bukkit.entity.Player;
import org.bukkit.event.Cancellable;
import org.bukkit.event.Event;
import org.bukkit.event.HandlerList;
import org.jetbrains.annotations.NotNull;

/**
 * Called when a request is accepted, before the trade window opens. Cancelling it stops the trade.
 * {@link #first()} is the player who sent the request.
 */
public final class TradeStartEvent extends Event implements Cancellable {
    private static final HandlerList HANDLERS = new HandlerList();

    private final Player first;
    private final Player second;
    private boolean cancelled;

    public TradeStartEvent(Player first, Player second) {
        this.first = first;
        this.second = second;
    }

    public Player first() { return first; }
    public Player second() { return second; }

    @Override public boolean isCancelled() { return cancelled; }
    @Override public void setCancelled(boolean cancel) { cancelled = cancel; }

    @Override public @NotNull HandlerList getHandlers() { return HANDLERS; }
    public static HandlerList getHandlerList() { return HANDLERS; }
}
