package com.mystipixel.econguard.core;

import com.mystipixel.econguard.api.Flag;
import com.mystipixel.econguard.api.MoneyEvent;
import com.mystipixel.econguard.data.Ledger;
import com.mystipixel.econguard.util.Text;
import org.bukkit.Bukkit;
import org.bukkit.Statistic;
import org.bukkit.entity.Player;
import org.bukkit.plugin.java.JavaPlugin;

import java.time.Instant;
import java.util.ArrayDeque;
import java.util.Deque;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Cross-flow anti-abuse analysis. Runs on the main thread for each reported {@link MoneyEvent}.
 *
 * Key idea: wealth alone is not suspicious - legit players get rich too. The signals here target
 * wealth that arrives FAST and with NO time invested (the RMT fingerprint), and money funneled
 * repeatedly between the same two accounts (collusion). The velocity / large-incoming signals only
 * fire on "young" accounts, so a veteran selling a lucky drop is not flagged.
 */
public final class AbuseMonitor {
    private record Sample(long time, double amount) {
    }

    private final JavaPlugin plugin;
    private final Ledger ledger;
    private final Alerter alerter;
    private final Map<UUID, Deque<Sample>> velocityWindows = new ConcurrentHashMap<>();
    private final Map<String, Deque<Sample>> pairWindows = new ConcurrentHashMap<>();
    // Cache of immutable first-played timestamps for OFFLINE uuids, to avoid repeated disk lookups on
    // the per-event hot path. Online players read straight off the live Player object (already cached).
    private final Map<UUID, Long> firstPlayedCache = new ConcurrentHashMap<>();

    public AbuseMonitor(JavaPlugin plugin, Ledger ledger, Alerter alerter) {
        this.plugin = plugin;
        this.ledger = ledger;
        this.alerter = alerter;
    }

    public void analyze(MoneyEvent event) {
        long now = Instant.now().getEpochSecond();
        String symbol = plugin.getConfig().getString("currency-symbol", "$");

        // 1. Large single transaction (informational alert, any account).
        double largeThreshold = plugin.getConfig().getDouble("detection.large-transaction", 100_000_000.0);
        if (largeThreshold > 0 && event.amount() >= largeThreshold) {
            alerter.alert("&c[EconGuard] &e" + safe(event.playerName()) + " &7" + event.action() + " &f"
                    + Text.money(event.amount(), symbol) + " &7via " + event.source()
                    + (event.hasCounterparty() ? " &7(party: &f" + safe(event.counterpartyName()) + "&7)" : ""));
        }

        // The remaining signals are about money ARRIVING to a player.
        if (!event.incoming()) {
            return;
        }
        boolean young = isYoungAccount(event.player());

        // 2. A young account receiving a large single transfer from another player.
        double youngIncoming = plugin.getConfig().getDouble("detection.young-incoming-transfer", 50_000_000.0);
        if (young && event.hasCounterparty() && youngIncoming > 0 && event.amount() >= youngIncoming) {
            raise(event.player(), event.playerName(), "young-incoming",
                    "New account received " + Text.money(event.amount(), symbol) + " from "
                            + safe(event.counterpartyName()) + " via " + event.source());
        }

        // 3. Velocity: a young account gaining too much, too fast. Only tracked for young accounts -
        //    veterans can never trip this signal, so we don't accumulate windows for them.
        if (young && plugin.getConfig().getBoolean("detection.velocity.enabled", true)) {
            long windowMinutes = Math.max(1L, plugin.getConfig().getLong("detection.velocity.window-minutes", 30L));
            double threshold = plugin.getConfig().getDouble("detection.velocity.threshold", 250_000_000.0);
            Deque<Sample> window = velocityWindows.computeIfAbsent(event.player(), k -> new ArrayDeque<>());
            double total = windowSum(window, now, windowMinutes * 60L, event.amount());
            if (threshold > 0 && total >= threshold) {
                raise(event.player(), event.playerName(), "velocity",
                        "New account gained " + Text.money(total, symbol) + " within " + windowMinutes + "m");
            }
        }

        // 4. Counterparty correlation: same payer funneling money to the same receiver (any age).
        if (plugin.getConfig().getBoolean("detection.counterparty.enabled", true) && event.hasCounterparty()) {
            long windowMinutes = Math.max(1L, plugin.getConfig().getLong("detection.counterparty.window-minutes", 60L));
            double threshold = plugin.getConfig().getDouble("detection.counterparty.threshold", 500_000_000.0);
            String key = event.counterparty() + ">" + event.player();
            Deque<Sample> window = pairWindows.computeIfAbsent(key, k -> new ArrayDeque<>());
            double total = windowSum(window, now, windowMinutes * 60L, event.amount());
            if (threshold > 0 && total >= threshold) {
                String detail = Text.money(total, symbol) + " moved " + safe(event.counterpartyName())
                        + " -> " + safe(event.playerName()) + " within " + windowMinutes + "m (possible collusion)";
                // Flag BOTH ends of the suspected ring (each dedup'd independently).
                raise(event.player(), event.playerName(), "collusion", "Received: " + detail);
                raise(event.counterparty(), event.counterpartyName(), "collusion", "Sent: " + detail);
            }
        }
    }

    public void clearVelocity(UUID uuid) {
        velocityWindows.remove(uuid);
    }

    /**
     * Drops window entries whose samples have all aged out, bounding memory. pairWindows is keyed by
     * (payer,receiver) pairs that are never tied to a session, so without this sweep it would grow
     * unbounded. Runs on the main thread (scheduled by the plugin).
     */
    public void sweep() {
        long now = Instant.now().getEpochSecond();
        long velocitySeconds = Math.max(1L, plugin.getConfig().getLong("detection.velocity.window-minutes", 30L)) * 60L;
        long pairSeconds = Math.max(1L, plugin.getConfig().getLong("detection.counterparty.window-minutes", 60L)) * 60L;
        pruneStale(velocityWindows, now, velocitySeconds);
        pruneStale(pairWindows, now, pairSeconds);
        // The first-played cache holds immutable values; clearing it just drops a perf cache (rebuilt on demand).
        firstPlayedCache.clear();
    }

    private static <K> void pruneStale(Map<K, Deque<Sample>> windows, long now, long windowSeconds) {
        long cutoff = now - windowSeconds;
        windows.entrySet().removeIf(entry -> {
            Deque<Sample> deque = entry.getValue();
            while (!deque.isEmpty() && deque.peekFirst().time() < cutoff) {
                deque.removeFirst();
            }
            return deque.isEmpty();
        });
    }

    private void raise(UUID uuid, String name, String type, String reason) {
        // Already flagged -> keep the existing flag, don't spam alerts while it awaits review.
        if (ledger.isFlagged(uuid)) {
            return;
        }
        ledger.saveFlag(new Flag(uuid, safe(name), type, reason, Instant.now().getEpochSecond()));
        alerter.alert("&4[EconGuard] FLAGGED &e" + safe(name) + " &7- " + reason
                + ". &7Review: &f/econguard history " + safe(name));
    }

    private boolean isYoungAccount(UUID uuid) {
        long maxAgeDays = Math.max(0L, plugin.getConfig().getLong("young-account.max-age-days", 3L));
        long maxPlaytimeHours = Math.max(0L, plugin.getConfig().getLong("young-account.max-playtime-hours", 10L));

        // Prefer the live Player: getFirstPlayed() and getStatistic() are in-memory (no disk hit). Only
        // fall back to a (cached) OfflinePlayer lookup for an offline uuid, e.g. an offline counterparty.
        Player online = Bukkit.getPlayer(uuid);
        long firstPlayed = online != null
                ? online.getFirstPlayed()
                : firstPlayedCache.computeIfAbsent(uuid, id -> Bukkit.getOfflinePlayer(id).getFirstPlayed());

        if (firstPlayed <= 0L) {
            return true; // never seen before / unknown -> treat as brand new
        }
        if ((System.currentTimeMillis() - firstPlayed) / 86_400_000L < maxAgeDays) {
            return true;
        }
        if (online != null) {
            long playtimeHours = online.getStatistic(Statistic.PLAY_ONE_MINUTE) / 20L / 3600L;
            return playtimeHours < maxPlaytimeHours;
        }
        return false;
    }

    private static double windowSum(Deque<Sample> window, long now, long windowSeconds, double newAmount) {
        window.addLast(new Sample(now, newAmount));
        long cutoff = now - windowSeconds;
        while (!window.isEmpty() && window.peekFirst().time() < cutoff) {
            window.removeFirst();
        }
        double sum = 0.0;
        for (Sample sample : window) {
            sum += sample.amount();
        }
        return sum;
    }

    private static String safe(String name) {
        return name == null ? "Unknown" : name;
    }
}
