package com.mystipixel.econguard;

import com.mystipixel.econguard.api.EconGuardAPI;
import com.mystipixel.econguard.command.EconGuardCommand;
import com.mystipixel.econguard.core.Alerter;
import com.mystipixel.econguard.core.AbuseMonitor;
import com.mystipixel.econguard.core.EconGuardService;
import com.mystipixel.econguard.data.Ledger;
import org.bukkit.command.PluginCommand;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.plugin.ServicePriority;
import org.bukkit.plugin.java.JavaPlugin;

public final class EconGuardPlugin extends JavaPlugin implements Listener {
    private Ledger ledger;
    private AbuseMonitor monitor;
    private EconGuardService service;

    @Override
    public void onEnable() {
        saveDefaultConfig();
        new com.mystipixel.econguard.config.ConfigValidator(this).validate();

        this.ledger = new Ledger(this);
        if (!ledger.connect()) {
            getLogger().severe("Could not open the EconGuard database. Plugin will disable.");
            getServer().getPluginManager().disablePlugin(this);
            return;
        }

        Alerter alerter = new Alerter(this);
        this.monitor = new AbuseMonitor(this, ledger, alerter);
        this.service = new EconGuardService(this, ledger, monitor);

        getServer().getServicesManager().register(EconGuardAPI.class, service, this, ServicePriority.Normal);

        // Flush the append-only audit queue off the main thread (every second).
        getServer().getScheduler().runTaskTimerAsynchronously(this, ledger::flush, 20L, 20L);
        // Periodically bound the in-memory detection windows (every 5 minutes).
        getServer().getScheduler().runTaskTimer(this, monitor::sweep, 6000L, 6000L);

        PluginCommand command = getCommand("econguard");
        if (command != null) {
            EconGuardCommand handler = new EconGuardCommand(this, service);
            command.setExecutor(handler);
            command.setTabCompleter(handler);
        }

        getServer().getPluginManager().registerEvents(this, this);
        pruneLedger();
        getLogger().info("EconGuard enabled. Economy audit + anti-abuse core is active.");
    }

    @Override
    public void onDisable() {
        if (service != null) {
            getServer().getServicesManager().unregister(EconGuardAPI.class, service);
        }
        if (ledger != null) {
            ledger.close();
        }
    }

    @EventHandler
    public void onPlayerQuit(PlayerQuitEvent event) {
        if (monitor != null) {
            monitor.clearVelocity(event.getPlayer().getUniqueId());
        }
    }

    private void pruneLedger() {
        int maxPerPlayer = getConfig().getInt("database.max-rows-per-player", 500);
        if (maxPerPlayer <= 0) {
            return;
        }
        getServer().getScheduler().runTaskAsynchronously(this, () -> {
            int removed = ledger.prune(maxPerPlayer);
            if (removed > 0) {
                getLogger().info("Pruned " + removed + " old ledger rows (keeping newest " + maxPerPlayer + " per player).");
            }
        });
    }
}
