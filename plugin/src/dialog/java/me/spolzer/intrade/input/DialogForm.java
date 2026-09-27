package me.spolzer.intrade.input;

import io.papermc.paper.dialog.Dialog;
import io.papermc.paper.registry.data.dialog.ActionButton;
import io.papermc.paper.registry.data.dialog.DialogBase;
import io.papermc.paper.registry.data.dialog.action.DialogAction;
import io.papermc.paper.registry.data.dialog.action.DialogActionCallback;
import io.papermc.paper.registry.data.dialog.body.DialogBody;
import io.papermc.paper.registry.data.dialog.input.DialogInput;
import io.papermc.paper.registry.data.dialog.type.DialogType;
import java.lang.invoke.MethodHandle;
import java.lang.invoke.MethodHandles;
import java.lang.invoke.MethodType;
import java.time.Duration;
import java.util.List;
import java.util.function.Consumer;
import net.kyori.adventure.audience.Audience;
import net.kyori.adventure.text.event.ClickCallback;
import org.bukkit.entity.Player;

// Created by AmountPrompt through reflection, only on servers with the dialog API
final class DialogForm implements AmountForm {
    private static final ClickCallback.Options ONCE = ClickCallback.Options.builder()
            .uses(1)
            .lifetime(Duration.ofMinutes(10))
            .build();
    private static final MethodHandle CLOSE_DIALOG = closeDialog();

    @Override
    public void show(Player player, Request request, Consumer<String> answer) {
        DialogActionCallback confirm = (view, audience) -> answer.accept(view.getText("amount"));
        DialogActionCallback everything = (view, audience) -> answer.accept(request.all());
        DialogActionCallback cancel = (view, audience) -> answer.accept(null);

        player.showDialog(Dialog.create(factory -> factory.empty()
                .base(DialogBase.builder(request.title())
                        .canCloseWithEscape(false)
                        .body(request.body().stream().<DialogBody>map(DialogBody::plainMessage).toList())
                        .inputs(List.of(DialogInput.text("amount", request.label())
                                .initial(request.initial())
                                .maxLength(24)
                                .build()))
                        .build())
                .type(DialogType.multiAction(
                        List.of(
                                ActionButton.create(request.confirm(), null, 100, DialogAction.customClick(confirm, ONCE)),
                                ActionButton.create(request.allButton(), null, 100, DialogAction.customClick(everything, ONCE))),
                        ActionButton.create(request.cancel(), null, 200, DialogAction.customClick(cancel, ONCE)),
                        2))));
    }

    // closeDialog was added in 1.21.8. On 1.21.7 the dialog stays open until a button is pressed,
    // and the answer is ignored because the trade has ended.
    @Override
    public void close(Player player) {
        if (CLOSE_DIALOG == null) return;
        try {
            CLOSE_DIALOG.invoke(player);
        } catch (Throwable ignored) {
            // The dialog is closed anyway once the player answers
        }
    }

    private static MethodHandle closeDialog() {
        try {
            return MethodHandles.publicLookup().findVirtual(Audience.class, "closeDialog", MethodType.methodType(void.class));
        } catch (ReflectiveOperationException e) {
            return null;
        }
    }
}
