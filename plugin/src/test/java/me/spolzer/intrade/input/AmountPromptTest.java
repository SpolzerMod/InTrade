package me.spolzer.intrade.input;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.File;
import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.function.Consumer;
import me.spolzer.intrade.FakeCurrency;
import me.spolzer.intrade.InTradePlugin;
import me.spolzer.intrade.api.currency.TradeCurrency;
import me.spolzer.intrade.currency.Currency;
import me.spolzer.intrade.currency.CurrencyException;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.entity.Player;
import org.bukkit.plugin.Plugin;
import org.bukkit.plugin.ServicePriority;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockbukkit.mockbukkit.MockBukkit;
import org.mockbukkit.mockbukkit.ServerMock;
import org.mockbukkit.mockbukkit.entity.PlayerMock;

class AmountPromptTest {
    private ServerMock server;
    private InTradePlugin plugin;
    private Plugin provider;
    private FakeCurrency tokens;
    private PlayerMock alice;
    private final FakeForm form = new FakeForm();
    private final List<BigDecimal> results = new ArrayList<>();
    private final List<RuntimeException> failures = new ArrayList<>();
    private AmountPrompt prompt;

    @BeforeEach
    void start() throws Exception {
        server = MockBukkit.mock();
        plugin = MockBukkit.load(InTradePlugin.class);
        provider = MockBukkit.createMockPlugin("Provider");
        File file = new File(plugin.getDataFolder(), "config.yml");
        YamlConfiguration config = YamlConfiguration.loadConfiguration(file);
        config.set("currencies.tokens.enabled", true);
        config.save(file);
        CompletableFuture<Void> reload = plugin.reload();
        for (int i = 0; i < 400 && !reload.isDone(); i++) {
            server.getScheduler().performOneTick();
            Thread.sleep(5);
        }
        reload.join();
        tokens = register();
        alice = server.addPlayer("alice");
        prompt = new AmountPrompt(plugin, form);
    }

    @AfterEach
    void stop() {
        MockBukkit.unmock();
    }

    @Test
    void answerIsParsedWithTheCurrentCurrency() {
        ask();
        form.answer("abc");
        assertEquals(2, form.shown);
        form.answer("12");
        assertEquals(List.of(new BigDecimal("12")), results);
    }

    @Test
    void answerAfterUnregisterDoesNotCallTheOldProvider() {
        ask();
        server.getServicesManager().unregister(TradeCurrency.class, tokens);
        tokens.retired = true;

        form.answer("5");
        assertEquals(0, tokens.retiredCalls);
        assertEquals(1, results.size());
        assertNull(results.getFirst());
    }

    @Test
    void answerAfterReplacementDoesNotCallTheOldProvider() {
        ask();
        server.getServicesManager().unregister(TradeCurrency.class, tokens);
        tokens.retired = true;
        register();

        form.answer("abc");
        assertEquals(0, tokens.retiredCalls);
        assertEquals(1, form.shown, "no retry with the old currency");
        assertEquals(1, results.size());
        assertNull(results.getFirst());
    }

    @Test
    void failureWhileAskingAgainIsReported() {
        ask();
        tokens.balanceFailsFor = alice.getUniqueId();
        form.answer("abc");
        assertTrue(results.isEmpty());
        assertEquals(1, failures.size());
        assertInstanceOf(CurrencyException.class, failures.getFirst());
    }

    private void ask() {
        Currency currency = plugin.currencies().byId("tokens");
        assertNotNull(currency);
        prompt.ask(alice, currency, BigDecimal.ZERO, results::add, failures::add);
        assertEquals(1, form.shown);
    }

    private FakeCurrency register() {
        FakeCurrency currency = new FakeCurrency("tokens", new ArrayList<>());
        server.getServicesManager().register(TradeCurrency.class, currency, provider, ServicePriority.Normal);
        return currency;
    }

    private static final class FakeForm implements AmountForm {
        int shown;
        private Consumer<String> answer;

        @Override
        public void show(Player player, Request request, Consumer<String> answer) {
            shown++;
            this.answer = answer;
        }

        @Override
        public void close(Player player) {
        }

        void answer(String text) {
            answer.accept(text);
        }
    }
}
