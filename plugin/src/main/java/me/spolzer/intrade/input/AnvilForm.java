package me.spolzer.intrade.input;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.function.Consumer;
import me.spolzer.intrade.menu.Icons;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.TextDecoration;
import net.kyori.adventure.text.serializer.legacy.LegacyComponentSerializer;
import org.bukkit.Bukkit;
import org.bukkit.Material;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.inventory.InventoryClickEvent;
import org.bukkit.event.inventory.InventoryCloseEvent;
import org.bukkit.event.inventory.InventoryDragEvent;
import org.bukkit.event.inventory.PrepareAnvilEvent;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.InventoryView;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.view.AnvilView;
import org.bukkit.plugin.Plugin;

/**
 * Amount input for servers without dialogs. The amount is typed into the rename field of an anvil: the left slot
 * holds the hint, the right one is the "all" button, the result slot confirms, closing the window cancels.
 */
final class AnvilForm implements AmountForm, Listener {
    private static final int ALL = 1;
    private static final int CONFIRM = 2;

    private final Plugin plugin;
    private final Map<UUID, Open> open = new HashMap<>();

    AnvilForm(Plugin plugin) {
        this.plugin = plugin;
    }

    private static final class Open {
        final Player player;
        final Inventory top;
        final Request request;
        final Consumer<String> answer;
        boolean answered;

        Open(Player player, Inventory top, Request request, Consumer<String> answer) {
            this.player = player;
            this.top = top;
            this.request = request;
            this.answer = answer;
        }
    }

    // MenuType, which replaces openAnvil and setTitle, appeared in 1.21.4
    @Override
    @SuppressWarnings("deprecation")
    public void show(Player player, Request request, Consumer<String> answer) {
        InventoryView view = player.openAnvil(null, true);
        if (view == null) {
            answer.accept(null);
            return;
        }
        view.setTitle(LegacyComponentSerializer.legacySection().serialize(request.title()));
        Inventory top = view.getTopInventory();
        open.put(player.getUniqueId(), new Open(player, top, request, answer));
        // The client fills the rename field with the name of the left item
        top.setItem(0, Icons.icon(Material.PAPER, plain(Component.text(request.initial())), plain(request.body())));
        top.setItem(ALL, Icons.icon(Material.GOLD_NUGGET, plain(request.allButton())));
    }

    @Override
    public void close(Player player) {
        Open form = open.get(player.getUniqueId());
        if (form == null) return;
        form.answered = true;
        if (viewing(form)) player.closeInventory();
        else open.remove(player.getUniqueId());
    }

    private static boolean viewing(Open form) {
        return form.player.isOnline() && form.player.getOpenInventory().getTopInventory() == form.top;
    }

    private Open formOf(InventoryView view) {
        Open form = open.get(view.getPlayer().getUniqueId());
        return form != null && view.getTopInventory() == form.top ? form : null;
    }

    @EventHandler(priority = EventPriority.HIGHEST)
    public void onPrepare(PrepareAnvilEvent event) {
        Open form = formOf(event.getView());
        if (form == null) return;
        event.setResult(Icons.icon(Material.PAPER, plain(form.request.confirm())));
        event.getView().setRepairCost(0);
        // The client computes the result itself and clears it, because paper and the "all" item do not combine.
        // The server result has not changed, so it is not sent again without a full update.
        Bukkit.getScheduler().runTask(plugin, () -> {
            if (viewing(form)) form.player.updateInventory();
        });
    }

    @EventHandler(priority = EventPriority.LOWEST)
    public void onClick(InventoryClickEvent event) {
        Open form = formOf(event.getView());
        if (form == null) return;
        event.setCancelled(true);
        if (form.answered || event.getClickedInventory() != form.top) return;
        String text;
        if (event.getRawSlot() == CONFIRM) {
            String typed = ((AnvilView) event.getView()).getRenameText();
            text = typed != null ? typed : form.request.initial();
        } else if (event.getRawSlot() == ALL) {
            text = form.request.all();
        } else {
            return;
        }
        form.answered = true;
        // The answer usually opens another window, which should not happen inside the click event
        Bukkit.getScheduler().runTask(plugin, () -> {
            form.answer.accept(text);
            if (viewing(form)) form.player.closeInventory();
        });
    }

    @EventHandler(priority = EventPriority.LOWEST)
    public void onDrag(InventoryDragEvent event) {
        if (formOf(event.getView()) != null) event.setCancelled(true);
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onClose(InventoryCloseEvent event) {
        Open form = formOf(event.getView());
        if (form == null) return;
        // An anvil gives its input items back to the player when it closes
        form.top.clear();
        open.remove(event.getPlayer().getUniqueId());
        if (form.answered) return;
        form.answered = true;
        Bukkit.getScheduler().runTask(plugin, () -> form.answer.accept(null));
    }

    private static Component plain(Component text) {
        return text.decorationIfAbsent(TextDecoration.ITALIC, TextDecoration.State.FALSE);
    }

    private static List<Component> plain(List<Component> lines) {
        return lines.stream().map(AnvilForm::plain).toList();
    }
}
