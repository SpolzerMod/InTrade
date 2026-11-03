package me.spolzer.intrade.listener;

import me.spolzer.intrade.InTradePlugin;
import me.spolzer.intrade.api.currency.TradeCurrency;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.server.ServiceRegisterEvent;
import org.bukkit.event.server.ServiceUnregisterEvent;
import org.bukkit.plugin.RegisteredServiceProvider;

/** Picks up currencies that other plugins register or unregister while InTrade is running. */
public final class ServiceListener implements Listener {
    private final InTradePlugin plugin;

    public ServiceListener(InTradePlugin plugin) {
        this.plugin = plugin;
    }

    @EventHandler
    public void onRegister(ServiceRegisterEvent event) {
        changed(event.getProvider());
    }

    @EventHandler
    public void onUnregister(ServiceUnregisterEvent event) {
        changed(event.getProvider());
    }

    private void changed(RegisteredServiceProvider<?> provider) {
        if (provider.getService() == TradeCurrency.class) plugin.reloadCurrencies();
    }
}
