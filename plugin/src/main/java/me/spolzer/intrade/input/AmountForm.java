package me.spolzer.intrade.input;

import java.util.List;
import java.util.function.Consumer;
import net.kyori.adventure.text.Component;
import org.bukkit.entity.Player;

/** Window in which a Java player types an amount: a dialog since 1.21.7, an anvil before. */
interface AmountForm {

    /** Shows the form. The answer is the typed text, or null if the player cancelled. */
    void show(Player player, Request request, Consumer<String> answer);

    /** Closes the form without an answer. */
    void close(Player player);

    record Request(Component title, List<Component> body, Component label, String initial, String all,
                   Component confirm, Component allButton, Component cancel) {
    }
}
