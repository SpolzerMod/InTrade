package me.spolzer.intrade;

import java.io.File;
import java.sql.SQLException;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.logging.Level;
import me.spolzer.intrade.api.InTrade;
import me.spolzer.intrade.command.TradeCommand;
import me.spolzer.intrade.config.Settings;
import me.spolzer.intrade.currency.Currencies;
import me.spolzer.intrade.hook.TradePlaceholders;
import me.spolzer.intrade.input.AmountPrompt;
import me.spolzer.intrade.listener.MenuListener;
import me.spolzer.intrade.listener.PlayerListener;
import me.spolzer.intrade.stats.Stats;
import me.spolzer.intrade.storage.TradeStorage;
import me.spolzer.intrade.text.Arg;
import me.spolzer.intrade.text.Messages;
import me.spolzer.intrade.trade.RequestManager;
import me.spolzer.intrade.trade.TradeManager;
import org.bukkit.Bukkit;
import org.bukkit.command.PluginCommand;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;
import org.bukkit.permissions.Permission;
import org.bukkit.permissions.PermissionDefault;
import org.bukkit.plugin.ServicePriority;
import org.bukkit.plugin.java.JavaPlugin;

public final class InTradePlugin extends JavaPlugin {
    private final Currencies currencies = new Currencies();
    private final Messages messages = new Messages(this);
    private final MainThread mainThread = new MainThread(getLogger());
    private Settings settings;
    private TradeStorage storage;
    private Stats stats;
    private TradeManager trades;
    private RequestManager requests;
    private AmountPrompt prompts;

    @Override
    public void onEnable() {
        saveDefaultConfig();
        // Read synchronously here: the server does not accept players until all plugins are enabled
        apply(readConfig());

        storage = new TradeStorage(settings.database(), new File(getDataFolder(), "trades.db"), getLogger());
        try {
            int recovered = storage.open();
            if (recovered > 0) {
                getLogger().warning("Recovered items from " + recovered + " interrupted trade(s), they will be returned when the owners join");
            }
        } catch (SQLException | RuntimeException e) {
            String where = settings.database().mysql() ? "the MySQL database" : "trades.db";
            getLogger().log(Level.SEVERE, "Could not open " + where + ", disabling InTrade", e);
            getServer().getPluginManager().disablePlugin(this);
            return;
        }
        if (settings.historyEnabled()) storage.purgeOlderThan(settings.historyKeepDays());

        stats = new Stats(storage, mainThread);
        trades = new TradeManager(this);
        requests = new RequestManager(this);
        prompts = new AmountPrompt(this);

        getServer().getPluginManager().registerEvents(new MenuListener(), this);
        getServer().getPluginManager().registerEvents(new PlayerListener(this), this);
        PluginCommand command = getCommand("trade");
        TradeCommand executor = new TradeCommand(this);
        command.setExecutor(executor);
        command.setTabCompleter(executor);
        getServer().getServicesManager().register(InTrade.class, new TradeService(this), this, ServicePriority.Normal);

        // Economy plugins register with Vault in their own onEnable
        Bukkit.getScheduler().runTask(this, () -> {
            currencies.load(settings, messages.locale(), getLogger());
            prompts.hookFloodgate();
            if (Bukkit.getPluginManager().isPluginEnabled("PlaceholderAPI")) new TradePlaceholders(this).register();
            for (Player player : Bukkit.getOnlinePlayers()) stats.load(player.getUniqueId());
        });

        Bukkit.getScheduler().runTaskTimer(this, mainThread::drain, 1L, 1L);
        Bukkit.getScheduler().runTaskTimer(this, () -> {
            trades.tick();
            requests.tick();
        }, 5L, 5L);
        Bukkit.getScheduler().runTaskTimer(this, stats::refreshTop, 20L, 20L * 60);
    }

    @Override
    public void onDisable() {
        if (trades != null) trades.shutdown();
        if (storage != null) storage.close();
        // Callbacks of the operations that close() waited for
        mainThread.drain();
    }

    /** Re-reads config.yml and the language files off the main thread, then applies them on it. */
    public CompletableFuture<Void> reload() {
        return CompletableFuture.supplyAsync(this::readConfig, task -> getServer().getScheduler().runTaskAsynchronously(this, task))
                .thenAcceptAsync(loaded -> {
                    apply(loaded);
                    currencies.load(settings, messages.locale(), getLogger());
                }, mainThread);
    }

    private record Loaded(Settings settings, Messages.Catalog catalog) {
    }

    private Loaded readConfig() {
        YamlConfiguration config = YamlConfiguration.loadConfiguration(new File(getDataFolder(), "config.yml"));
        Settings loaded = Settings.load(config, getLogger());
        return new Loaded(loaded, messages.read(loaded.language(), loaded.defaultLanguage()));
    }

    private void apply(Loaded loaded) {
        settings = loaded.settings();
        messages.use(loaded.catalog());
        setDefault("intrade.player", settings.openToEveryone() ? PermissionDefault.TRUE : PermissionDefault.FALSE);
    }

    private void setDefault(String name, PermissionDefault value) {
        Permission permission = getServer().getPluginManager().getPermission(name);
        if (permission == null) return;
        if (permission.getDefault() != value) permission.setDefault(value);
        for (String child : permission.getChildren().keySet()) setDefault(child, value);
    }

    public void deliverMail(Player player, boolean reportEmpty) {
        storage.takeMail(player.getUniqueId()).thenAcceptAsync(items -> {
            if (items.isEmpty()) {
                if (reportEmpty) messages.send(player, "mail.empty");
                return;
            }
            if (!player.isOnline()) {
                storage.addMail(player.getUniqueId(), items);
                return;
            }
            List<ItemStack> rest = List.copyOf(player.getInventory().addItem(items.toArray(new ItemStack[0])).values());
            // The mail is already deleted, so save the player now instead of waiting for the next autosave
            player.saveData();
            if (!rest.isEmpty()) {
                storage.addMail(player.getUniqueId(), rest);
                messages.send(player, "mail.partial", Arg.of("count", rest.size()));
            }
            if (items.size() > rest.size()) messages.send(player, "mail.delivered", Arg.of("count", items.size() - rest.size()));
        }, mainThread);
    }

    public Settings settings() { return settings; }
    public Messages messages() { return messages; }
    public Currencies currencies() { return currencies; }
    public TradeStorage storage() { return storage; }
    public Stats stats() { return stats; }
    public TradeManager trades() { return trades; }
    public RequestManager requests() { return requests; }
    public AmountPrompt prompts() { return prompts; }
    public MainThread mainThread() { return mainThread; }
}
