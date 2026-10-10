package com.mystipixel.econguard.service;

import com.mystipixel.econguard.api.Flag;
import com.mystipixel.econguard.api.MoneyEvent;
import com.mystipixel.econguard.data.Ledger;
import com.mystipixel.econguard.util.Text;
import org.bukkit.Bukkit;
import org.bukkit.OfflinePlayer;
import org.bukkit.Statistic;
import org.bukkit.entity.Player;
import org.bukkit.plugin.java.JavaPlugin;

import java.time.Clock;
import java.util.ArrayDeque;
import java.util.Collections;
import java.util.Deque;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;
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

    private record AccountAge(long firstPlayed, long playtimeTicks) {
    }

    private static final int AGE_CACHE_SIZE = 10_000;

    private final JavaPlugin plugin;
    private final Ledger ledger;
    private final Alerter alerter;
    // "uuid:type" flags whose save is still in flight, so a burst of events can't write the same flag twice.
    private final Set<String> pendingFlags = ConcurrentHashMap.newKeySet();
    private final Map<UUID, Deque<Sample>> velocityWindows = new ConcurrentHashMap<>();
    private final Map<String, Deque<Sample>> pairWindows = new ConcurrentHashMap<>();
    // Offline accounts only (online players are read live). Filled on quit and by an async read, since
    // an OfflinePlayer lookup reads playerdata and stats files from disk.
    private final Map<UUID, AccountAge> offlineAges = Collections.synchronizedMap(
            new LinkedHashMap<>(256, 0.75f, true) {
                @Override
                protected boolean removeEldestEntry(Map.Entry<UUID, AccountAge> eldest) {
                    return size() > AGE_CACHE_SIZE;
                }
            });
    private final Set<UUID> loadingAges = ConcurrentHashMap.newKeySet();

    private final Clock clock;

    public AbuseMonitor(JavaPlugin plugin, Ledger ledger, Alerter alerter) {
        this(plugin, ledger, alerter, Clock.systemUTC());
    }

    AbuseMonitor(JavaPlugin plugin, Ledger ledger, Alerter alerter, Clock clock) {
        this.plugin = plugin;
        this.ledger = ledger;
        this.alerter = alerter;
        this.clock = clock;
    }

    public void analyze(MoneyEvent event) {
        long now = clock.instant().getEpochSecond();
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

    /**
     * Drops window entries whose samples have all aged out, bounding memory. pairWindows is keyed by
     * (payer,receiver) pairs that are never tied to a session, so without this sweep it would grow
     * unbounded. Runs on the main thread (scheduled by the plugin).
     */
    public void sweep() {
        long now = clock.instant().getEpochSecond();
        long velocitySeconds = Math.max(1L, plugin.getConfig().getLong("detection.velocity.window-minutes", 30L)) * 60L;
        long pairSeconds = Math.max(1L, plugin.getConfig().getLong("detection.counterparty.window-minutes", 60L)) * 60L;
        pruneStale(velocityWindows, now, velocitySeconds);
        pruneStale(pairWindows, now, pairSeconds);
    }

    /** Remembers a leaving player's age so later payments to them while offline need no disk read. */
    public void rememberAge(Player player) {
        offlineAges.put(player.getUniqueId(), ageOf(player));
    }

    private static AccountAge ageOf(OfflinePlayer player) {
        long firstPlayed = player.getFirstPlayed();
        return new AccountAge(firstPlayed, firstPlayed > 0L ? player.getStatistic(Statistic.PLAY_ONE_MINUTE) : 0L);
    }

    private void loadAgeAsync(UUID uuid) {
        if (!loadingAges.add(uuid)) {
            return;
        }
        plugin.getServer().getScheduler().runTaskAsynchronously(plugin, () -> {
            try {
                offlineAges.put(uuid, ageOf(Bukkit.getOfflinePlayer(uuid)));
            } finally {
                loadingAges.remove(uuid);
            }
        });
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
        // Dedup on the flag cache (loaded from the database, cleared with the flag), so clearing a flag
        // re-arms detection. The pending key only covers the gap until the async save lands.
        String key = uuid + ":" + type;
        if (ledger.isFlaggedFast(uuid, type) || !pendingFlags.add(key)) {
            return;
        }
        String safeName = safe(name);
        long now = clock.instant().getEpochSecond();
        plugin.getServer().getScheduler().runTaskAsynchronously(plugin, () -> {
            try {
                if (ledger.saveFlag(new Flag(uuid, safeName, type, reason, now))) {
                    plugin.getServer().getScheduler().runTask(plugin, () ->
                            alerter.alert("&4[EconGuard] FLAGGED &e" + safeName + " &7- " + reason
                                    + ". &7Review: &f/econguard history " + safeName));
                }
            } finally {
                pendingFlags.remove(key);
            }
        });
    }

    private boolean isYoungAccount(UUID uuid) {
        long maxAgeDays = Math.max(0L, plugin.getConfig().getLong("young-account.max-age-days", 3L));
        long maxPlaytimeHours = Math.max(0L, plugin.getConfig().getLong("young-account.max-playtime-hours", 10L));

        Player online = Bukkit.getPlayer(uuid);
        AccountAge age = online != null ? ageOf(online) : offlineAges.get(uuid);
        if (age == null) {
            loadAgeAsync(uuid);
            return true; // unknown until the async read lands; young is the safe side
        }
        if (age.firstPlayed() <= 0L) {
            return true; // never seen before / unknown -> treat as brand new
        }
        if ((clock.millis() - age.firstPlayed()) / 86_400_000L < maxAgeDays) {
            return true;
        }
        return age.playtimeTicks() / 20L / 3600L < maxPlaytimeHours;
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
