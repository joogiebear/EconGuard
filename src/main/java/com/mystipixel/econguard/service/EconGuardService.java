package com.mystipixel.econguard.service;

import com.mystipixel.econguard.api.EconGuardAPI;
import com.mystipixel.econguard.api.Flag;
import com.mystipixel.econguard.api.MoneyEvent;
import com.mystipixel.econguard.data.Ledger;
import org.bukkit.Bukkit;
import org.bukkit.plugin.java.JavaPlugin;

import java.util.List;
import java.util.UUID;

public final class EconGuardService implements EconGuardAPI {
    private final JavaPlugin plugin;
    private final Ledger ledger;
    private final AbuseMonitor monitor;

    public EconGuardService(JavaPlugin plugin, Ledger ledger, AbuseMonitor monitor) {
        this.plugin = plugin;
        this.ledger = ledger;
        this.monitor = monitor;
    }

    @Override
    public void record(MoneyEvent event) {
        if (event == null || event.player() == null) {
            return;
        }
        // The ledger (shared connection) and analysis (Bukkit player state) are main-thread only.
        // If a feature plugin reports from an async task, bounce to the main thread instead of risking
        // a corrupt read/write.
        if (!Bukkit.isPrimaryThread()) {
            Bukkit.getScheduler().runTask(plugin, () -> process(event));
            return;
        }
        process(event);
    }

    private void process(MoneyEvent event) {
        // Auditing must never throw back into a caller's committed money transaction.
        try {
            ledger.record(event);
            monitor.analyze(event);
        } catch (Throwable throwable) {
            plugin.getLogger().warning("EconGuard failed to process a money event for "
                    + event.player() + ": " + throwable.getMessage());
        }
    }

    @Override
    public List<Flag> getFlags() {
        return ledger.getFlags();
    }

    @Override
    public boolean clearFlag(UUID player) {
        return ledger.clearFlag(player);
    }

    @Override
    public void clearFlags() {
        ledger.clearFlags();
    }

    @Override
    public List<MoneyEvent> getHistory(UUID player, int limit) {
        return ledger.recent(player, limit);
    }

    @Override
    public boolean isFlagged(UUID player) {
        return player != null && ledger.isFlaggedFast(player);
    }

    @Override
    public boolean allowTrade(UUID player) {
        if (!plugin.getConfig().getBoolean("enforcement.block-flagged-trades", false)) {
            return true;
        }
        return !isFlagged(player);
    }

    /** Recent ledger rows between two specific players, either direction, newest first. */
    public List<MoneyEvent> pairRecent(UUID a, UUID b, int limit) {
        return ledger.pairRecent(a, b, limit);
    }

    /** Totals received by each side of a pair from the other: {@code player -> {count, sum}}. */
    public java.util.Map<UUID, double[]> pairTotals(UUID a, UUID b) {
        return ledger.pairTotals(a, b);
    }

    public AbuseMonitor monitor() {
        return monitor;
    }

    public long ledgerCount() {
        return ledger.countLedger();
    }
}
