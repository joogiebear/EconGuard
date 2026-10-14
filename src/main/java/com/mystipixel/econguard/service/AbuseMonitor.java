package com.mystipixel.econguard.service;

import com.mystipixel.econguard.api.Flag;
import com.mystipixel.econguard.api.MoneyEvent;
import com.mystipixel.econguard.data.Ledger;
import com.mystipixel.econguard.message.MessageManager;
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
 * Anti-abuse analysis, run on the main thread for each {@link MoneyEvent}. Wealth alone isn't suspicious:
 * the signals target money that arrives fast on young accounts, and money funneled repeatedly between
 * the same two accounts.
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
    private final MessageManager messages;
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

    // Cross-server window checks: a few threads and a bounded queue, so a burst of payments can't start a
    // thread or a query each. A check dropped when the queue is full runs again with the next payment.
    private final java.util.concurrent.Executor checks;
    private final Set<String> checking = ConcurrentHashMap.newKeySet();

    public AbuseMonitor(JavaPlugin plugin, Ledger ledger, Alerter alerter, MessageManager messages) {
        this(plugin, ledger, alerter, messages, Clock.systemUTC(), checkPool());
    }

    // tests: checks run inline
    AbuseMonitor(JavaPlugin plugin, Ledger ledger, Alerter alerter, MessageManager messages, Clock clock) {
        this(plugin, ledger, alerter, messages, clock, Runnable::run);
    }

    AbuseMonitor(JavaPlugin plugin, Ledger ledger, Alerter alerter, MessageManager messages, Clock clock,
                 java.util.concurrent.Executor checks) {
        this.plugin = plugin;
        this.ledger = ledger;
        this.alerter = alerter;
        this.messages = messages;
        this.clock = clock;
        this.checks = checks;
    }

    private static java.util.concurrent.ThreadPoolExecutor checkPool() {
        java.util.concurrent.ThreadPoolExecutor pool = new java.util.concurrent.ThreadPoolExecutor(2, 2,
                30L, java.util.concurrent.TimeUnit.SECONDS, new java.util.concurrent.ArrayBlockingQueue<>(256),
                runnable -> {
                    Thread thread = new Thread(runnable, "EconGuard-window-check");
                    thread.setDaemon(true);
                    return thread;
                },
                (dropped, executor) -> {
                    throw new java.util.concurrent.RejectedExecutionException("window checks are busy");
                });
        pool.allowCoreThreadTimeOut(true);
        return pool;
    }

    /** Stops the cross-server checks. Call on disable. */
    public void close() {
        if (checks instanceof java.util.concurrent.ExecutorService pool) {
            pool.shutdownNow();
        }
    }

    public void analyze(MoneyEvent event) {
        long now = clock.instant().getEpochSecond();
        String symbol = plugin.getConfig().getString("currency-symbol", "$");

        double largeThreshold = plugin.getConfig().getDouble("detection.large-transaction", 100_000_000.0);
        if (largeThreshold > 0 && event.amount() >= largeThreshold) {
            String party = event.hasCounterparty()
                    ? messages.get("alerts.large-transaction-party", "counterparty", safe(event.counterpartyName())) : "";
            alerter.alert(messages.get("alerts.large-transaction", "player", safe(event.playerName()),
                    "action", event.action(), "amount", Text.money(event.amount(), symbol),
                    "source", event.source(), "party", party));
        }

        if (!event.incoming()) {
            return;
        }
        boolean young = isYoungAccount(event.player());

        double youngIncoming = plugin.getConfig().getDouble("detection.young-incoming-transfer", 50_000_000.0);
        if (young && event.hasCounterparty() && youngIncoming > 0 && event.amount() >= youngIncoming) {
            String amount = Text.money(event.amount(), symbol);
            String counterparty = safe(event.counterpartyName());
            raise(event.player(), event.playerName(), "young-incoming",
                    "New account received " + amount + " from " + counterparty + " via " + event.source(),
                    messages.get("reasons.young-incoming", "amount", amount, "counterparty", counterparty,
                            "source", event.source()));
        }

        // young accounts only, so no windows build up for veterans
        if (young && plugin.getConfig().getBoolean("detection.velocity.enabled", true)) {
            long windowMinutes = Math.max(1L, plugin.getConfig().getLong("detection.velocity.window-minutes", 30L));
            double threshold = plugin.getConfig().getDouble("detection.velocity.threshold", 250_000_000.0);
            Deque<Sample> window = velocityWindows.computeIfAbsent(event.player(), k -> new ArrayDeque<>());
            double total = windowSum(window, now, windowMinutes * 60L, event.amount());
            if (threshold > 0 && total >= threshold) {
                raiseVelocity(event, total, windowMinutes, symbol);
            } else if (threshold > 0) {
                acrossServers(event.player(), null, "velocity", now - windowMinutes * 60L, threshold,
                        sum -> raiseVelocity(event, sum, windowMinutes, symbol));
            }
        }

        if (plugin.getConfig().getBoolean("detection.counterparty.enabled", true) && event.hasCounterparty()) {
            long windowMinutes = Math.max(1L, plugin.getConfig().getLong("detection.counterparty.window-minutes", 60L));
            double threshold = plugin.getConfig().getDouble("detection.counterparty.threshold", 500_000_000.0);
            String key = event.counterparty() + ">" + event.player();
            Deque<Sample> window = pairWindows.computeIfAbsent(key, k -> new ArrayDeque<>());
            double total = windowSum(window, now, windowMinutes * 60L, event.amount());
            if (threshold > 0 && total >= threshold) {
                raiseCollusion(event, total, windowMinutes, symbol);
            } else if (threshold > 0) {
                acrossServers(event.player(), event.counterparty(), "collusion", now - windowMinutes * 60L, threshold,
                        sum -> raiseCollusion(event, sum, windowMinutes, symbol));
            }
        }
    }

    private void raiseVelocity(MoneyEvent event, double total, long windowMinutes, String symbol) {
        String amount = Text.money(total, symbol);
        raise(event.player(), event.playerName(), "velocity",
                "New account gained " + amount + " within " + windowMinutes + "m",
                messages.get("reasons.velocity", "amount", amount, "minutes", String.valueOf(windowMinutes)));
    }

    private void raiseCollusion(MoneyEvent event, double total, long windowMinutes, String symbol) {
        String amount = Text.money(total, symbol);
        String payer = safe(event.counterpartyName());
        String receiver = safe(event.playerName());
        String detail = amount + " moved " + payer + " -> " + receiver + " within " + windowMinutes
                + "m (possible collusion)";
        String[] values = {"amount", amount, "payer", payer, "receiver", receiver,
                "minutes", String.valueOf(windowMinutes)};
        raise(event.player(), event.playerName(), "collusion", "Received: " + detail,
                messages.get("reasons.collusion-received", values));
        raise(event.counterparty(), event.counterpartyName(), "collusion", "Sent: " + detail,
                messages.get("reasons.collusion-sent", values));
    }

    /**
     * With servers sharing the database, this server's window misses money the player received on the
     * others, so the shared ledger is totalled off the main thread and {@code over} runs back on it when
     * the total reaches the threshold. Single-server storage has nothing more to count.
     */
    private void acrossServers(UUID receiver, UUID payer, String type, long since, double threshold,
                               java.util.function.DoubleConsumer over) {
        if (!ledger.shared() || ledger.isFlaggedFast(receiver, type)) {
            return;
        }
        // one check per receiver (and payer) at a time; a payment arriving meanwhile is in the next one
        String key = type + ":" + receiver + ":" + payer;
        if (!checking.add(key)) {
            return;
        }
        try {
            checks.execute(() -> {
                try {
                    double total = ledger.receivedSince(receiver, payer, since);
                    if (total >= threshold && plugin.isEnabled()) {
                        plugin.getServer().getScheduler().runTask(plugin, () -> over.accept(total));
                    }
                } finally {
                    checking.remove(key);
                }
            });
        } catch (java.util.concurrent.RejectedExecutionException closed) {
            checking.remove(key);
        }
    }

    /** Drops windows whose samples have all aged out; pair windows would otherwise grow without bound. */
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

    // storedReason is kept with the flag as data, in a fixed wording; shownReason is the configured alert text
    private void raise(UUID uuid, String name, String type, String storedReason, String shownReason) {
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
                // a flag another server sharing the database already raised was announced there
                if (ledger.saveFlag(new Flag(uuid, safeName, type, storedReason, now)) == Ledger.FlagSave.CREATED) {
                    plugin.getServer().getScheduler().runTask(plugin, () -> alerter.alert(
                            messages.get("alerts.flagged", "player", safeName, "reason", shownReason)));
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
