package com.mystipixel.econguard.data;

import com.zaxxer.hikari.HikariConfig;
import com.zaxxer.hikari.HikariDataSource;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.ResultSet;
import java.sql.Statement;

import static org.junit.jupiter.api.Assertions.assertEquals;

// reads every pragma back off a real connection: a setting that doesn't apply fails silently otherwise
class SqliteSettingsTest {

    @TempDir
    Path dir;

    @Test
    @DisplayName("a pooled connection, configured as Ledger configures it, has every pragma")
    void everyPragmaReachesAPooledConnection() throws Exception {
        HikariConfig hikari = new HikariConfig();
        hikari.setJdbcUrl(url("pooled.db"));
        hikari.setDriverClassName("org.sqlite.JDBC");
        hikari.setMaximumPoolSize(SqliteSettings.POOL_SIZE);
        hikari.setDataSourceProperties(SqliteSettings.properties());

        try (HikariDataSource dataSource = new HikariDataSource(hikari);
             Connection c = dataSource.getConnection();
             Statement st = c.createStatement()) {
            assertAllPragmas(st);
        }
    }

    @Test
    @DisplayName("every new connection gets them, not just the first")
    void everyPragmaIsAppliedToEachNewConnection() throws Exception {
        for (int i = 0; i < 2; i++) {
            try (Connection c = DriverManager.getConnection(url("direct.db"), SqliteSettings.properties());
                 Statement st = c.createStatement()) {
                assertAllPragmas(st);
            }
        }
    }

    // Pins the driver behaviour SqliteSettings works around: Statement#execute, as HikariCP calls it for
    // connectionInitSql, runs only the first statement. If this fails, sqlite-jdbc changed.
    @Test
    @DisplayName("the old multi-statement init string stops after its first pragma")
    void multiStatementInitSqlStopsAfterTheFirstPragma() throws Exception {
        try (Connection c = DriverManager.getConnection(url("legacy.db"));
             Statement st = c.createStatement()) {
            st.execute("PRAGMA journal_mode=WAL; PRAGMA synchronous=NORMAL; PRAGMA busy_timeout=5000;");
            assertEquals("wal", pragma(st, "journal_mode").toLowerCase(), "the first pragma applies");
            assertEquals("3000", pragma(st, "busy_timeout"), "the last one does not: this is the driver default");
        }
    }

    private static void assertAllPragmas(Statement st) throws Exception {
        assertEquals("wal", pragma(st, "journal_mode").toLowerCase());
        assertEquals("1", pragma(st, "synchronous"), "synchronous NORMAL is 1");
        assertEquals(String.valueOf(SqliteSettings.BUSY_TIMEOUT_MS), pragma(st, "busy_timeout"));
    }

    private String url(String file) {
        return "jdbc:sqlite:" + dir.resolve(file);
    }

    private static String pragma(Statement st, String name) throws Exception {
        try (ResultSet rs = st.executeQuery("PRAGMA " + name)) {
            rs.next();
            return rs.getString(1);
        }
    }
}
