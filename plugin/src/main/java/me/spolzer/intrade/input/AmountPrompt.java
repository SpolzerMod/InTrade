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
import java.math.BigDecimal;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.function.Consumer;
import java.util.logging.Level;
import me.spolzer.intrade.InTradePlugin;
import me.spolzer.intrade.currency.Currencies;
import me.spolzer.intrade.currency.Currency;
import me.spolzer.intrade.text.Arg;
import me.spolzer.intrade.text.Messages;
import net.kyori.adventure.audience.Audience;
import net.kyori.adventure.text.event.ClickCallback;
import org.bukkit.Bukkit;
import org.bukkit.entity.Player;

public final class AmountPrompt {
    private static final ClickCallback.Options ONCE = ClickCallback.Options.builder()
            .uses(1)
            .lifetime(Duration.ofMinutes(10))
            .build();
    private static final MethodHandle CLOSE_DIALOG = closeDialog();

    private final InTradePlugin plugin;
    private FloodgateBridge floodgate;

    public AmountPrompt(InTradePlugin plugin) {
        this.plugin = plugin;
    }

    public void hookFloodgate() {
        if (!Bukkit.getPluginManager().isPluginEnabled("floodgate")) return;
        floodgate = new FloodgateBridge();
        plugin.getLogger().info("Floodgate found, Bedrock players will use native forms");
    }

    public void ask(Player player, Currency currency, BigDecimal current, Consumer<BigDecimal> result) {
        ask(player, currency, current, result, false);
    }

    private void ask(Player player, Currency currency, BigDecimal current, Consumer<BigDecimal> result, boolean retry) {
        Messages messages = plugin.messages();
        Arg[] args = {messages.currency(player, currency.id()), Arg.of("balance", currency.format(currency.balance(player)))};
        String initial = current.signum() > 0 ? plain(current) : "";

        Consumer<String> answer = text -> onMain(() -> {
            if (text == null) {
                result.accept(null);
                return;
            }
            BigDecimal amount = Currencies.parse(text, currency.scale());
            if (amount == null) {
                messages.send(player, "prompt.invalid", Arg.of("input", text));
                ask(player, currency, current, result, true);
                return;
            }
            result.accept(amount);
        });

        if (floodgate != null && floodgate.isBedrock(player)) {
            floodgate.askNumber(player, messages.plain(player, "prompt.title", args),
                    messages.plain(player, retry ? "prompt.bedrock-label-retry" : "prompt.bedrock-label", args), initial, answer);
            return;
        }

        List<DialogBody> body = new ArrayList<>();
        body.add(DialogBody.plainMessage(messages.get(player, "prompt.balance", args)));
        body.add(DialogBody.plainMessage(messages.get(player, "prompt.hint", args)));
        if (retry) body.add(DialogBody.plainMessage(messages.get(player, "prompt.retry", args)));

        String all = plain(currency.balance(player));
        DialogActionCallback confirm = (view, audience) -> answer.accept(view.getText("amount"));
        DialogActionCallback everything = (view, audience) -> answer.accept(all);
        DialogActionCallback cancel = (view, audience) -> answer.accept(null);

        player.showDialog(Dialog.create(factory -> factory.empty()
                .base(DialogBase.builder(messages.get(player, "prompt.title", args))
                        .canCloseWithEscape(false)
                        .body(body)
                        .inputs(List.of(DialogInput.text("amount", messages.get(player, "prompt.label", args))
                                .initial(initial)
                                .maxLength(24)
                                .build()))
                        .build())
                .type(DialogType.multiAction(
                        List.of(
                                ActionButton.create(messages.get(player, "prompt.confirm"), null, 100, DialogAction.customClick(confirm, ONCE)),
                                ActionButton.create(messages.get(player, "prompt.all", args), null, 100, DialogAction.customClick(everything, ONCE))),
                        ActionButton.create(messages.get(player, "prompt.cancel"), null, 200, DialogAction.customClick(cancel, ONCE)),
                        2))));
    }

    public void offerRequestForm(Player target, Player from, Runnable accept, Runnable deny) {
        if (floodgate == null || !floodgate.isBedrock(target)) return;
        Messages messages = plugin.messages();
        floodgate.askYesNo(target,
                messages.plain(target, "request.form.title"),
                messages.plain(target, "request.form.content", Arg.of("player", from.getName())),
                messages.plain(target, "request.form.accept"),
                messages.plain(target, "request.form.deny"),
                answer -> onMain(() -> {
                    if (Boolean.TRUE.equals(answer)) accept.run();
                    else if (Boolean.FALSE.equals(answer)) deny.run();
                }));
    }

    // closeDialog was added in 1.21.8. On 1.21.7 the dialog stays open until a button is pressed,
    // and the answer is ignored because the trade has ended.
    public void dismiss(Player player) {
        if (CLOSE_DIALOG == null) return;
        try {
            CLOSE_DIALOG.invoke(player);
        } catch (Throwable e) {
            plugin.getLogger().log(Level.FINE, "Could not close a dialog", e);
        }
    }

    private static MethodHandle closeDialog() {
        try {
            return MethodHandles.publicLookup().findVirtual(Audience.class, "closeDialog", MethodType.methodType(void.class));
        } catch (ReflectiveOperationException e) {
            return null;
        }
    }

    // Dialog callbacks arrive on the main thread, Floodgate form callbacks on a network thread
    private void onMain(Runnable task) {
        if (Bukkit.isPrimaryThread()) task.run();
        else plugin.mainThread().execute(task);
    }

    private static String plain(BigDecimal amount) {
        return amount.stripTrailingZeros().toPlainString();
    }
}
