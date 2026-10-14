package com.mystipixel.econguard.data;

import com.mystipixel.econguard.api.Flag;
import com.mystipixel.econguard.api.MoneyEvent;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.plugin.java.JavaPlugin;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.ResultSet;
import java.sql.Statement;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.UUID;
import java.util.logging.Logger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * Two ledgers on one database stand in for two servers. The SQLite cases share one file; the MySQL cases
 * run when EG_TEST_MYSQL_PORT is set (EG_TEST_MYSQL_USER and EG_TEST_MYSQL_PASSWORD default to root with
 * no password; the user needs CREATE DATABASE).
 */
class SharedLedgerTest {

    @TempDir
    Path dir;

    private final List<Ledger> opened = Collections.synchronizedList(new ArrayList<>());
    private final UUID alt = UUID.randomUUID();
    private final UUID payer = UUID.randomUUID();

    @AfterEach
    void close() {
        opened.forEach(Ledger::close);
    }

    @Test
    void aFlagIsCreatedOnceAndFoundExistingByTheNextServer() {
        Ledger a = sqlite("a");
        Ledger b = sqlite("b");
        assertEquals(Ledger.FlagSave.CREATED, a.saveFlag(flag("velocity", "first")));
        assertEquals(Ledger.FlagSave.EXISTING, b.saveFlag(flag("velocity", "second")));
        assertEquals("second", a.getFlags().get(0).reason(), "the existing flag takes the newer reason");
        assertTrue(b.isFlaggedFast(alt, "velocity"));
    }

    @Test
    void flagsRaisedAndClearedElsewhereArriveWithTheNextRefresh() {
        Ledger a = sqlite("a");
        Ledger b = sqlite("b");
        a.saveFlag(flag("collusion", "seen on a"));
        assertFalse(b.isFlaggedFast(alt));
        b.refreshFlags();
        assertTrue(b.isFlaggedFast(alt, "collusion"));

        assertTrue(a.clearFlag(alt));
        b.refreshFlags();
        assertFalse(b.isFlaggedFast(alt));
        assertEquals(0, b.flaggedCount());
    }

    @Test
    void paymentsRecordedOnEveryServerAddUp() {
        Ledger a = sqlite("a");
        Ledger b = sqlite("b");
        long since = Instant.now().getEpochSecond() - 60;
        a.record(payment(300, payer));
        b.record(payment(400, payer));
        b.record(payment(50, UUID.randomUUID()));
        a.flush();
        assertEquals(750.0, b.receivedSince(alt, null, since), 0.001);
        assertEquals(700.0, a.receivedSince(alt, payer, since), 0.001);
        assertEquals(0.0, a.receivedSince(payer, null, since), 0.001);
    }

    @Test
    @EnabledIfEnvironmentVariable(named = "EG_TEST_MYSQL_PORT", matches = "[0-9]+")
    void onMySqlFlagsAndPaymentsAreShared() throws Exception {
        String database = freshDatabase();
        Ledger a = mysql("a", database);
        Ledger b = mysql("b", database);
        assertTrue(a.shared());

        assertEquals(Ledger.FlagSave.CREATED, a.saveFlag(flag("velocity", "on a")));
        assertEquals(Ledger.FlagSave.EXISTING, b.saveFlag(flag("velocity", "on b")));
        b.refreshFlags();
        assertTrue(b.isFlaggedFast(alt, "velocity"));
        assertTrue(b.clearFlag(alt));
        a.refreshFlags();
        assertFalse(a.isFlaggedFast(alt));

        long since = Instant.now().getEpochSecond() - 60;
        a.record(payment(1000, payer));
        b.record(payment(2000, payer));
        b.flush();
        assertEquals(3000.0, a.receivedSince(alt, payer, since), 0.001);
    }

    @Test
    @EnabledIfEnvironmentVariable(named = "EG_TEST_MYSQL_PORT", matches = "[0-9]+")
    void onMySqlOnlyOneServerPrunesAtATime() throws Exception {
        String database = freshDatabase();
        Ledger a = mysql("a", database);
        Ledger b = mysql("b", database);
        for (int i = 0; i < 5; i++) {
            a.record(payment(1, payer));
        }
        assertEquals(3, a.prune(2));
        b.record(payment(1, payer));
        assertEquals(0, b.prune(2), "a still holds the prune lease");
        b.flush();
        assertEquals(1, a.prune(2), "the holder may prune again");
    }

    @Test
    @EnabledIfEnvironmentVariable(named = "EG_TEST_MYSQL_PORT", matches = "[0-9]+")
    void onMySqlServersStartingTogetherMigrateOnce() throws Exception {
        String database = freshDatabase();
        List<Thread> threads = new ArrayList<>();
        for (int i = 0; i < 4; i++) {
            String id = "s" + i;
            threads.add(new Thread(() -> mysql(id, database)));
        }
        threads.forEach(Thread::start);
        for (Thread t : threads) {
            t.join();
        }
        assertEquals(4, opened.size());
        try (Connection c = DriverManager.getConnection(mysqlUrl(database), user(), password());
             Statement st = c.createStatement();
             ResultSet rs = st.executeQuery("SELECT version FROM econguard_schema_version")) {
            assertTrue(rs.next());
            assertEquals(Ledger.SCHEMA_VERSION, rs.getInt(1));
        }
    }

    private Flag flag(String type, String reason) {
        return new Flag(alt, "alt", type, reason, Instant.now().getEpochSecond());
    }

    private MoneyEvent payment(double amount, UUID from) {
        return MoneyEvent.builder(alt, "alt").source("bank").action("transfer-received")
                .amount(amount).incoming(true).counterparty(from, "payer").build();
    }

    private Ledger sqlite(String serverId) {
        YamlConfiguration config = new YamlConfiguration();
        config.set("storage.type", "SQLITE");
        // relative to each server's own data folder, so both open the same file
        config.set("storage.sqlite-file", "../econguard.db");
        config.set("storage.server-id", serverId);
        return open(serverId, config);
    }

    private Ledger mysql(String serverId, String database) {
        YamlConfiguration config = new YamlConfiguration();
        config.set("storage.type", "MYSQL");
        config.set("storage.server-id", serverId);
        config.set("storage.mysql.host", "127.0.0.1");
        config.set("storage.mysql.port", Integer.parseInt(System.getenv("EG_TEST_MYSQL_PORT")));
        config.set("storage.mysql.database", database);
        config.set("storage.mysql.username", user());
        config.set("storage.mysql.password", password());
        config.set("storage.mysql.pool-size", 4);
        return open(serverId, config);
    }

    private Ledger open(String serverId, YamlConfiguration config) {
        try {
            Path folder = Files.createDirectories(dir.resolve(serverId));
            JavaPlugin plugin = mock(JavaPlugin.class);
            when(plugin.getConfig()).thenReturn(config);
            when(plugin.getDataFolder()).thenReturn(folder.toFile());
            when(plugin.getLogger()).thenReturn(Logger.getLogger("econguard-test-" + serverId));
            Ledger ledger = new Ledger(plugin);
            assertTrue(ledger.connect(), "ledger " + serverId + " connects");
            opened.add(ledger);
            return ledger;
        } catch (java.io.IOException e) {
            throw new RuntimeException(e);
        }
    }

    private static String freshDatabase() throws Exception {
        String name = "sdb_egtest_" + UUID.randomUUID().toString().replace("-", "").substring(0, 12);
        try (Connection c = DriverManager.getConnection(mysqlUrl(""), user(), password());
             Statement st = c.createStatement()) {
            st.executeUpdate("CREATE DATABASE " + name);
        }
        return name;
    }

    private static String mysqlUrl(String database) {
        return "jdbc:mysql://127.0.0.1:" + System.getenv("EG_TEST_MYSQL_PORT") + "/" + database
                + "?useSSL=false&allowPublicKeyRetrieval=true";
    }

    private static String user() {
        return System.getenv().getOrDefault("EG_TEST_MYSQL_USER", "root");
    }

    private static String password() {
        return System.getenv().getOrDefault("EG_TEST_MYSQL_PASSWORD", "");
    }
}
