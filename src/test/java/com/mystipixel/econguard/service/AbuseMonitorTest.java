package com.mystipixel.econguard.service;

import com.mystipixel.econguard.api.MoneyEvent;
import com.mystipixel.econguard.data.Ledger;
import org.bukkit.Bukkit;
import org.bukkit.OfflinePlayer;
import org.bukkit.Server;
import org.bukkit.Statistic;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.entity.Player;
import org.bukkit.plugin.java.JavaPlugin;
import org.bukkit.scheduler.BukkitScheduler;
import org.bukkit.scheduler.BukkitTask;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.mockito.MockedStatic;

import java.nio.file.Path;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.Statement;
import java.util.Map;
import java.util.UUID;
import java.util.logging.Logger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.contains;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.mockStatic;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/** Flag dedup against a real SQLite ledger, with Bukkit's scheduler running tasks inline. */
class AbuseMonitorTest {
    @TempDir
    Path folder;

    private final UUID alt = UUID.randomUUID();
    private final UUID payer = UUID.randomUUID();
    private JavaPlugin plugin;
    private YamlConfiguration config;
    private Ledger ledger;
    private Alerter alerter;
    private AbuseMonitor monitor;
    private MockedStatic<Bukkit> bukkit;
    private BukkitScheduler scheduler;
    private final MutableClock clock = new MutableClock();

    @BeforeEach
    void setUp() {
        config = new YamlConfiguration();
        config.set("detection.large-transaction", 0);
        config.set("detection.young-incoming-transfer", 100.0);
        config.set("detection.velocity.threshold", 0);
        config.set("detection.counterparty.threshold", 0);
        plugin = mock(JavaPlugin.class);
        when(plugin.getConfig()).thenReturn(config);
        when(plugin.getDataFolder()).thenReturn(folder.toFile());
        when(plugin.getLogger()).thenReturn(Logger.getLogger("test"));
        Server server = mock(Server.class);
        scheduler = mock(BukkitScheduler.class);
        when(plugin.getServer()).thenReturn(server);
        when(server.getScheduler()).thenReturn(scheduler);
        when(scheduler.runTask(eq(plugin), any(Runnable.class))).thenAnswer(inline());
        when(scheduler.runTaskAsynchronously(eq(plugin), any(Runnable.class))).thenAnswer(inline());

        bukkit = mockStatic(Bukkit.class);
        Player online = mock(Player.class);
        when(online.getFirstPlayed()).thenReturn(System.currentTimeMillis());
        bukkit.when(() -> Bukkit.getPlayer(alt)).thenReturn(online);

        ledger = new Ledger(plugin);
        assertTrue(ledger.connect());
        alerter = mock(Alerter.class);
        monitor = new AbuseMonitor(plugin, ledger, alerter, clock);
    }

    @AfterEach
    void tearDown() {
        ledger.close();
        bukkit.close();
    }

    private static org.mockito.stubbing.Answer<BukkitTask> inline() {
        return invocation -> {
            invocation.<Runnable>getArgument(1).run();
            return mock(BukkitTask.class);
        };
    }

    private MoneyEvent transfer(double amount) {
        return MoneyEvent.builder(alt, "alt").source("bank").action("transfer-received")
                .amount(amount).incoming(true).counterparty(payer, "payer").build();
    }

    @Test
    void aClearedPlayerCanBeFlaggedAgain() {
        monitor.analyze(transfer(500));
        assertTrue(ledger.isFlaggedFast(alt));
        assertTrue(ledger.clearFlag(alt));

        monitor.analyze(transfer(500));

        assertTrue(ledger.isFlaggedFast(alt));
        verify(alerter, times(2)).alert(contains("FLAGGED"));
    }

    @Test
    void anExistingFlagIsNotRaisedTwice() {
        monitor.analyze(transfer(500));
        monitor.analyze(transfer(500));

        verify(alerter, times(1)).alert(contains("FLAGGED"));
    }

    @Test
    void aPlayerCanHoldASecondFlagType() {
        config.set("detection.counterparty.threshold", 1000.0);
        monitor.analyze(transfer(500));
        monitor.analyze(transfer(600));

        assertTrue(ledger.isFlaggedFast(alt, "young-incoming"));
        assertTrue(ledger.isFlaggedFast(alt, "collusion"));
        assertEquals(2, ledger.getFlags().stream().filter(f -> f.player().equals(alt)).count());
    }

    @Test
    void aFailedSaveIsRetriedOnTheNextEvent() {
        Ledger failing = mock(Ledger.class);
        when(failing.saveFlag(any())).thenReturn(false, true);
        AbuseMonitor flaky = new AbuseMonitor(plugin, failing, alerter);

        flaky.analyze(transfer(500));
        verify(alerter, never()).alert(anyString());
        flaky.analyze(transfer(500));

        verify(failing, times(2)).saveFlag(any());
        verify(alerter, times(1)).alert(contains("FLAGGED"));
    }

    @Test
    void anOldOneFlagPerPlayerTableIsMigrated() throws Exception {
        ledger.close();
        Path db = folder.resolve("econguard.db");
        try (Connection c = DriverManager.getConnection("jdbc:sqlite:" + db);
             Statement st = c.createStatement()) {
            st.executeUpdate("DROP TABLE flags");
            st.executeUpdate("CREATE TABLE flags (uuid TEXT PRIMARY KEY, username TEXT, type TEXT NOT NULL,"
                    + " reason TEXT NOT NULL, created_at INTEGER NOT NULL)");
            st.executeUpdate("INSERT INTO flags VALUES ('" + alt + "', 'alt', 'velocity', 'old', 1)");
        }
        ledger = new Ledger(plugin);
        assertTrue(ledger.connect());

        assertTrue(ledger.isFlaggedFast(alt, "velocity"));
        assertFalse(ledger.isFlaggedFast(alt, "collusion"));
        monitor = new AbuseMonitor(plugin, ledger, alerter, clock);
        monitor.analyze(transfer(500));
        assertEquals(2, ledger.getFlags().size());
    }

    @Test
    void velocityAddsUpAcrossSeparateEvents() {
        config.set("detection.young-incoming-transfer", 0);
        config.set("detection.velocity.threshold", 1000.0);
        monitor.analyze(transfer(600));
        assertFalse(ledger.isFlaggedFast(alt));
        clock.advanceSeconds(10 * 60);

        monitor.analyze(transfer(600));

        assertTrue(ledger.isFlaggedFast(alt, "velocity"));
    }

    @Test
    void sweepEvictsWindowsOnlyOnceTheirSamplesAgeOut() throws Exception {
        config.set("detection.young-incoming-transfer", 0);
        config.set("detection.velocity.threshold", 1000.0);
        config.set("detection.counterparty.threshold", 1000.0);
        monitor.analyze(transfer(100));

        clock.advanceSeconds(29 * 60);
        monitor.sweep();
        assertEquals(1, window(monitor, "velocityWindows").size());
        assertEquals(1, window(monitor, "pairWindows").size());

        clock.advanceSeconds(2 * 60);
        monitor.sweep();
        assertTrue(window(monitor, "velocityWindows").isEmpty());
        assertEquals(1, window(monitor, "pairWindows").size());

        clock.advanceSeconds(30 * 60);
        monitor.sweep();
        assertTrue(window(monitor, "pairWindows").isEmpty());
    }

    private <T extends OfflinePlayer> T account(Class<T> type, long daysOld, long hoursPlayed) {
        T player = mock(type);
        when(player.getUniqueId()).thenReturn(alt);
        when(player.getFirstPlayed()).thenReturn(clock.millis() - daysOld * 86_400_000L);
        when(player.getStatistic(Statistic.PLAY_ONE_MINUTE)).thenReturn((int) (hoursPlayed * 3600 * 20));
        return player;
    }

    private void goOffline() {
        bukkit.when(() -> Bukkit.getPlayer(alt)).thenReturn(null);
    }

    @Test
    void anOfflineAltWithLittlePlaytimeIsYoung() {
        OfflinePlayer offline = account(OfflinePlayer.class, 30, 1);
        goOffline();
        bukkit.when(() -> Bukkit.getOfflinePlayer(alt)).thenReturn(offline);

        monitor.analyze(transfer(500));

        assertTrue(ledger.isFlaggedFast(alt, "young-incoming"));
    }

    @Test
    void anOfflineVeteranIsNotYoungOnceItsAgeIsKnown() {
        OfflinePlayer offline = account(OfflinePlayer.class, 30, 500);
        goOffline();
        bukkit.when(() -> Bukkit.getOfflinePlayer(alt)).thenReturn(offline);
        BukkitScheduler deferred = mock(BukkitScheduler.class);
        when(plugin.getServer().getScheduler()).thenReturn(deferred);
        java.util.List<Runnable> queued = new java.util.ArrayList<>();
        when(deferred.runTaskAsynchronously(eq(plugin), any(Runnable.class))).thenAnswer(invocation -> {
            queued.add(invocation.getArgument(1));
            return mock(BukkitTask.class);
        });

        monitor.analyze(transfer(500));
        bukkit.verify(() -> Bukkit.getOfflinePlayer(alt), never());
        assertEquals(2, queued.size(), "an age load and a flag save, both off the main thread");

        when(plugin.getServer().getScheduler()).thenReturn(scheduler);
        queued.forEach(Runnable::run);
        assertTrue(ledger.clearFlag(alt));
        monitor.analyze(transfer(500));

        assertFalse(ledger.isFlaggedFast(alt));
    }

    @Test
    void quittingRemembersTheAccountAge() {
        Player leaving = account(Player.class, 30, 500);
        monitor.rememberAge(leaving);
        goOffline();

        monitor.analyze(transfer(500));

        bukkit.verify(() -> Bukkit.getOfflinePlayer(alt), never());
        assertFalse(ledger.isFlaggedFast(alt));
    }

    private static Map<?, ?> window(AbuseMonitor monitor, String field) throws Exception {
        var declared = AbuseMonitor.class.getDeclaredField(field);
        declared.setAccessible(true);
        return (Map<?, ?>) declared.get(monitor);
    }

    private static final class MutableClock extends Clock {
        private Instant now = Instant.parse("2026-10-10T12:00:00Z");

        void advanceSeconds(long seconds) {
            now = now.plusSeconds(seconds);
        }

        @Override
        public Instant instant() {
            return now;
        }

        @Override
        public java.time.ZoneId getZone() {
            return ZoneOffset.UTC;
        }

        @Override
        public Clock withZone(java.time.ZoneId zone) {
            return this;
        }
    }
}
