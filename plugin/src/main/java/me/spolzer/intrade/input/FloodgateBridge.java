package me.spolzer.intrade.input;

import java.util.function.Consumer;
import org.bukkit.entity.Player;
import org.geysermc.cumulus.form.CustomForm;
import org.geysermc.cumulus.form.ModalForm;
import org.geysermc.floodgate.api.FloodgateApi;

final class FloodgateBridge {
    private final FloodgateApi api = FloodgateApi.getInstance();

    boolean isBedrock(Player player) {
        return api.isFloodgatePlayer(player.getUniqueId());
    }

    void askNumber(Player player, String title, String label, String initial, Consumer<String> answer) {
        CustomForm.Builder form = CustomForm.builder().title(title)
                .input(label, "0", initial)
                .validResultHandler(response -> answer.accept(response.asInput(0)))
                .closedOrInvalidResultHandler(() -> answer.accept(null));
        if (!api.sendForm(player.getUniqueId(), form)) answer.accept(null);
    }

    void askYesNo(Player player, String title, String content, String yes, String no, Consumer<Boolean> answer) {
        api.sendForm(player.getUniqueId(), ModalForm.builder().title(title).content(content)
                .button1(yes)
                .button2(no)
                .validResultHandler(response -> answer.accept(response.clickedFirst()))
                .closedOrInvalidResultHandler(() -> answer.accept(null)));
    }
}
