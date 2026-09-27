package me.spolzer.intrade.input;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;
import java.util.function.Consumer;
import me.spolzer.intrade.InTradePlugin;
import me.spolzer.intrade.currency.Currencies;
import me.spolzer.intrade.currency.Currency;
import me.spolzer.intrade.text.Arg;
import me.spolzer.intrade.text.Messages;
import net.kyori.adventure.text.Component;
import org.bukkit.Bukkit;
import org.bukkit.entity.Player;

public final class AmountPrompt {
    private final InTradePlugin plugin;
    private final AmountForm form;
    private FloodgateBridge floodgate;

    public AmountPrompt(InTradePlugin plugin) {
        this.plugin = plugin;
        this.form = createForm(plugin);
    }

    private static AmountForm createForm(InTradePlugin plugin) {
        try {
            Class.forName("io.papermc.paper.dialog.Dialog");
            // Compiled separately against 1.21.7, see the dialog source set
            return Class.forName("me.spolzer.intrade.input.DialogForm").asSubclass(AmountForm.class)
                    .getDeclaredConstructor().newInstance();
        } catch (ReflectiveOperationException e) {
            plugin.getLogger().info("This server version has no dialogs, amounts are entered in an anvil");
            AnvilForm anvil = new AnvilForm(plugin);
            plugin.getServer().getPluginManager().registerEvents(anvil, plugin);
            return anvil;
        }
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

        List<Component> body = new ArrayList<>();
        body.add(messages.get(player, "prompt.balance", args));
        body.add(messages.get(player, "prompt.hint", args));
        if (retry) body.add(messages.get(player, "prompt.retry", args));
        if (form instanceof AnvilForm) body.add(messages.get(player, "prompt.close-to-cancel"));
        form.show(player, new AmountForm.Request(messages.get(player, "prompt.title", args), body,
                messages.get(player, "prompt.label", args), initial, plain(currency.balance(player)),
                messages.get(player, "prompt.confirm"), messages.get(player, "prompt.all", args),
                messages.get(player, "prompt.cancel")), answer);
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

    public void dismiss(Player player) {
        form.close(player);
    }

    // Dialog and anvil answers arrive on the main thread, Floodgate form callbacks on a network thread
    private void onMain(Runnable task) {
        if (Bukkit.isPrimaryThread()) task.run();
        else plugin.mainThread().execute(task);
    }

    private static String plain(BigDecimal amount) {
        return amount.stripTrailingZeros().toPlainString();
    }
}
