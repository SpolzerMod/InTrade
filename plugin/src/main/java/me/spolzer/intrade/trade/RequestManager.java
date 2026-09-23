package me.spolzer.intrade.trade;

import java.util.HashMap;
import java.util.HashSet;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import me.spolzer.intrade.InTradePlugin;
import me.spolzer.intrade.api.event.TradeRequestEvent;
import me.spolzer.intrade.config.Settings;
import me.spolzer.intrade.text.Arg;
import me.spolzer.intrade.text.Messages;
import net.kyori.adventure.text.event.ClickEvent;
import org.bukkit.Bukkit;
import org.bukkit.entity.Player;

public final class RequestManager {
    private final InTradePlugin plugin;
    // Target -> sender -> expiry time, in order of arrival
    private final Map<UUID, LinkedHashMap<UUID, Long>> incoming = new HashMap<>();
    private final Map<UUID, Long> lastSent = new HashMap<>();
    private final Set<UUID> muted = new HashSet<>();

    public RequestManager(InTradePlugin plugin) {
        this.plugin = plugin;
    }

    private Messages messages() { return plugin.messages(); }

    public boolean accepting(Player player) { return !muted.contains(player.getUniqueId()); }

    public void send(Player from, Player to) {
        if (from.equals(to)) {
            messages().send(from, "request.self");
            return;
        }
        if (pending(from, to)) {
            accept(from, to);
            return;
        }
        if (!canTrade(from, to)) return;
        if (muted.contains(to.getUniqueId())) {
            messages().send(from, "request.target-muted", name(to));
            return;
        }
        if (!to.hasPermission("intrade.use")) {
            messages().send(from, "request.target-no-permission", name(to));
            return;
        }
        LinkedHashMap<UUID, Long> requests = incoming.computeIfAbsent(to.getUniqueId(), id -> new LinkedHashMap<>());
        if (requests.containsKey(from.getUniqueId())) {
            messages().send(from, "request.already-sent", name(to));
            return;
        }
        long now = System.currentTimeMillis();
        Long last = lastSent.get(from.getUniqueId());
        long cooldown = plugin.settings().requestCooldownMillis;
        if (last != null && now - last < cooldown && !from.hasPermission("intrade.bypass.cooldown")) {
            messages().send(from, "request.cooldown", Arg.of("seconds", (cooldown - (now - last) + 999) / 1000));
            return;
        }
        TradeRequestEvent event = new TradeRequestEvent(from, to);
        Bukkit.getPluginManager().callEvent(event);
        if (event.isCancelled()) return;

        long expire = plugin.settings().requestExpireMillis;
        requests.put(from.getUniqueId(), now + expire);
        lastSent.put(from.getUniqueId(), now);
        Arg seconds = Arg.of("seconds", expire / 1000);
        messages().send(from, "request.sent", name(to), seconds);
        messages().send(to, "request.received", name(from), seconds,
                Arg.of("accept", messages().get(to, "request.accept-button", name(from))
                        .clickEvent(ClickEvent.runCommand("/trade accept " + from.getName()))),
                Arg.of("deny", messages().get(to, "request.deny-button", name(from))
                        .clickEvent(ClickEvent.runCommand("/trade deny " + from.getName()))));
        TradeSession.play(to, "block.note_block.bell", 1.3f);
        plugin.prompts().offerRequestForm(to, from,
                () -> { if (from.isOnline()) accept(to, from); },
                () -> { if (from.isOnline()) deny(to, from); });
    }

    public void accept(Player target, Player from) {
        LinkedHashMap<UUID, Long> requests = incoming.get(target.getUniqueId());
        if (requests == null || requests.isEmpty()) {
            messages().send(target, "request.none");
            return;
        }
        if (from == null) {
            from = latestOnline(requests);
            if (from == null) {
                messages().send(target, "request.none");
                return;
            }
        } else if (!requests.containsKey(from.getUniqueId())) {
            messages().send(target, "request.none-from", name(from));
            return;
        }
        requests.remove(from.getUniqueId());
        if (!canTrade(target, from)) return;
        incoming.remove(target.getUniqueId());
        incoming.remove(from.getUniqueId());
        plugin.trades().start(from, target);
    }

    public void deny(Player target, Player from) {
        LinkedHashMap<UUID, Long> requests = incoming.get(target.getUniqueId());
        if (from == null && requests != null) from = latestOnline(requests);
        if (from == null) {
            messages().send(target, "request.none");
            return;
        }
        if (requests == null || requests.remove(from.getUniqueId()) == null) {
            messages().send(target, "request.none-from", name(from));
            return;
        }
        messages().send(target, "request.denied", name(from));
        messages().send(from, "request.denied-by", name(target));
    }

    public boolean toggle(Player player) {
        if (muted.remove(player.getUniqueId())) return true;
        muted.add(player.getUniqueId());
        incoming.remove(player.getUniqueId());
        return false;
    }

    private boolean pending(Player target, Player from) {
        LinkedHashMap<UUID, Long> requests = incoming.get(target.getUniqueId());
        return requests != null && requests.containsKey(from.getUniqueId());
    }

    private static Player latestOnline(LinkedHashMap<UUID, Long> requests) {
        Player latest = null;
        for (UUID id : requests.keySet()) {
            Player player = Bukkit.getPlayer(id);
            if (player != null) latest = player;
        }
        return latest;
    }

    public void forget(Player player) {
        UUID id = player.getUniqueId();
        incoming.remove(id);
        lastSent.remove(id);
        for (LinkedHashMap<UUID, Long> requests : incoming.values()) requests.remove(id);
    }

    public void tick() {
        long now = System.currentTimeMillis();
        for (Iterator<Map.Entry<UUID, LinkedHashMap<UUID, Long>>> targets = incoming.entrySet().iterator(); targets.hasNext(); ) {
            Map.Entry<UUID, LinkedHashMap<UUID, Long>> entry = targets.next();
            Player target = Bukkit.getPlayer(entry.getKey());
            for (Iterator<Map.Entry<UUID, Long>> it = entry.getValue().entrySet().iterator(); it.hasNext(); ) {
                Map.Entry<UUID, Long> request = it.next();
                if (request.getValue() > now) continue;
                it.remove();
                Player sender = Bukkit.getPlayer(request.getKey());
                if (sender != null && target != null) messages().send(sender, "request.expired", name(target));
            }
            if (entry.getValue().isEmpty()) targets.remove();
        }
    }

    private boolean canTrade(Player actor, Player other) {
        Settings settings = plugin.settings();
        if (plugin.trades().inTrade(actor)) {
            messages().send(actor, "request.you-busy");
            return false;
        }
        if (plugin.trades().inTrade(other)) {
            messages().send(actor, "request.target-busy", name(other));
            return false;
        }
        if (settings.isWorldDisabled(actor.getWorld().getName()) || settings.isWorldDisabled(other.getWorld().getName())) {
            messages().send(actor, "request.world-disabled");
            return false;
        }
        if (actor.hasPermission("intrade.bypass.distance")) return true;
        boolean sameWorld = actor.getWorld().equals(other.getWorld());
        if (settings.sameWorld && !sameWorld) {
            messages().send(actor, "request.other-world", name(other));
            return false;
        }
        double max = settings.maxDistance;
        if (max > 0 && (!sameWorld || actor.getLocation().distanceSquared(other.getLocation()) > max * max)) {
            messages().send(actor, "request.too-far", name(other), Arg.of("distance", (int) max));
            return false;
        }
        return true;
    }

    private static Arg name(Player player) {
        return Arg.of("player", player.getName());
    }
}
