package com.mystipixel.econguard.service;

import com.mystipixel.econguard.api.MoneyEvent;
import com.mystipixel.econguard.data.Ledger;
import org.bukkit.Bukkit;
import org.bukkit.Server;
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
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.Statement;
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
        BukkitScheduler scheduler = mock(BukkitScheduler.class);
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
        monitor = new AbuseMonitor(plugin, ledger, alerter);
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
        monitor = new AbuseMonitor(plugin, ledger, alerter);
        monitor.analyze(transfer(500));
        assertEquals(2, ledger.getFlags().size());
    }
}
