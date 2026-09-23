package me.spolzer.intrade.api.event;

import org.bukkit.entity.Player;
import org.bukkit.event.Event;
import org.bukkit.event.HandlerList;
import org.jetbrains.annotations.NotNull;

/** Called when a trade ends without an exchange. Items are already returned to their owners. */
public final class TradeCancelEvent extends Event {
    private static final HandlerList HANDLERS = new HandlerList();

    public enum Reason { CANCELLED, LEFT, DIED, SHUTDOWN }

    private final Player first;
    private final Player second;
    private final Player cause;
    private final Reason reason;

    public TradeCancelEvent(Player first, Player second, Player cause, Reason reason) {
        this.first = first;
        this.second = second;
        this.cause = cause;
        this.reason = reason;
    }

    public Player first() { return first; }
    public Player second() { return second; }
    /** The player who cancelled, left or died, or null on server shutdown. */
    public Player cause() { return cause; }
    public Reason reason() { return reason; }

    @Override public @NotNull HandlerList getHandlers() { return HANDLERS; }
    public static HandlerList getHandlerList() { return HANDLERS; }
}
