package com.mystipixel.econguard.data;

import com.mystipixel.econguard.api.Flag;
import com.mystipixel.econguard.api.MoneyEvent;
import com.zaxxer.hikari.HikariConfig;
import com.zaxxer.hikari.HikariDataSource;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.plugin.java.JavaPlugin;

import java.io.File;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Queue;
import java.util.UUID;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.logging.Level;

/**
 * Unified ledger plus the persisted flag registry, over one HikariCP data source that serves both
 * SQLite (default, single server) and MySQL (networks / a shared audit DB across servers).
 *
 * <p>Audit rows are append-only, so {@link #record} just enqueues and a background {@link #flush}
 * batches the inserts — keeping high-volume audit writes off the main thread. The JDBC driver and the
 * connection pool are delivered by Paper's library loader (see plugin.yml {@code libraries}); nothing
 * is shaded into the jar.
 */
public final class Ledger {

    public enum Type { SQLITE, MYSQL }

    private static final int FLUSH_BATCH_CAP = 2000;
    private static final String INSERT_SQL = """
            INSERT INTO ledger (uuid, username, source, action, amount, incoming,
                                counterparty, counterparty_name, item, balance_after, note, created_at)
            VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
            """;

    private record Queued(MoneyEvent event, long time) {
    }

    private final JavaPlugin plugin;
    private final Queue<Queued> writeQueue = new ConcurrentLinkedQueue<>();

    private Type type;
    private HikariDataSource dataSource;

    public Ledger(JavaPlugin plugin) {
        this.plugin = plugin;
    }

    // ------------------------------------------------------------------ lifecycle

    public boolean connect() {
        try {
            ConfigurationSection storage = plugin.getConfig().getConfigurationSection("storage");
            if (storage == null) {
                storage = plugin.getConfig().createSection("storage");
            }
            String rawType = storage.getString("type", "SQLITE").toUpperCase(Locale.ROOT);
            this.type = "MYSQL".equals(rawType) ? Type.MYSQL : Type.SQLITE;

            HikariConfig hikari = new HikariConfig();
            hikari.setPoolName("EconGuard");

            if (type == Type.MYSQL) {
                ConfigurationSection my = storage.getConfigurationSection("mysql");
                if (my == null) {
                    my = storage.createSection("mysql");
                }
                String host = my.getString("host", "localhost");
                int port = my.getInt("port", 3306);
                String database = my.getString("database", "econguard");
                String props = my.getString("properties", "useSSL=false");
                loadDriver("com.mysql.cj.jdbc.Driver");
                hikari.setJdbcUrl("jdbc:mysql://" + host + ":" + port + "/" + database + "?" + props);
                hikari.setDriverClassName("com.mysql.cj.jdbc.Driver");
                hikari.setUsername(my.getString("username", "root"));
                hikari.setPassword(my.getString("password", ""));
                hikari.setMaximumPoolSize(Math.max(1, my.getInt("pool-size", 10)));
            } else {
                File dataFolder = plugin.getDataFolder();
                if (!dataFolder.exists() && !dataFolder.mkdirs()) {
                    plugin.getLogger().severe("Could not create data folder: " + dataFolder.getAbsolutePath());
                    return false;
                }
                File file = new File(dataFolder, storage.getString("sqlite-file", "econguard.db"));
                loadDriver("org.sqlite.JDBC");
                hikari.setJdbcUrl("jdbc:sqlite:" + file.getAbsolutePath());
                hikari.setDriverClassName("org.sqlite.JDBC");
                // One connection: SQLite is single-writer, so a pool of 1 avoids SQLITE_BUSY entirely.
                hikari.setMaximumPoolSize(1);
                hikari.setConnectionInitSql("PRAGMA journal_mode=WAL; PRAGMA synchronous=NORMAL; PRAGMA busy_timeout=5000;");
            }

            this.dataSource = new HikariDataSource(hikari);
            createTables();
            plugin.getLogger().info("EconGuard connected to " + type + " storage.");
            return true;
        } catch (Exception exception) {
            plugin.getLogger().severe("EconGuard storage init failed: " + exception.getMessage());
            return false;
        }
    }

    private void loadDriver(String driverClass) {
        try {
            Class.forName(driverClass, true, getClass().getClassLoader());
        } catch (ClassNotFoundException exception) {
            plugin.getLogger().log(Level.WARNING, "JDBC driver not found on classpath: " + driverClass, exception);
        }
    }

    private boolean mysql() {
        return type == Type.MYSQL;
    }

    private void createTables() throws SQLException {
        String ledgerDdl = mysql() ? """
                CREATE TABLE IF NOT EXISTS ledger (
                    id BIGINT PRIMARY KEY AUTO_INCREMENT,
                    uuid VARCHAR(36) NOT NULL,
                    username VARCHAR(32),
                    source VARCHAR(32) NOT NULL,
                    action VARCHAR(64) NOT NULL,
                    amount DOUBLE NOT NULL,
                    incoming TINYINT NOT NULL,
                    counterparty VARCHAR(36),
                    counterparty_name VARCHAR(32),
                    item VARCHAR(255),
                    balance_after DOUBLE,
                    note VARCHAR(1024),
                    created_at BIGINT NOT NULL,
                    KEY idx_ledger_uuid_created (uuid, created_at)
                )
                """ : """
                CREATE TABLE IF NOT EXISTS ledger (
                    id INTEGER PRIMARY KEY AUTOINCREMENT,
                    uuid TEXT NOT NULL,
                    username TEXT,
                    source TEXT NOT NULL,
                    action TEXT NOT NULL,
                    amount REAL NOT NULL,
                    incoming INTEGER NOT NULL,
                    counterparty TEXT,
                    counterparty_name TEXT,
                    item TEXT,
                    balance_after REAL,
                    note TEXT,
                    created_at INTEGER NOT NULL
                )
                """;

        String flagsDdl = mysql() ? """
                CREATE TABLE IF NOT EXISTS flags (
                    uuid VARCHAR(36) PRIMARY KEY,
                    username VARCHAR(32),
                    type VARCHAR(64) NOT NULL,
                    reason VARCHAR(512) NOT NULL,
                    created_at BIGINT NOT NULL
                )
                """ : """
                CREATE TABLE IF NOT EXISTS flags (
                    uuid TEXT PRIMARY KEY,
                    username TEXT,
                    type TEXT NOT NULL,
                    reason TEXT NOT NULL,
                    created_at INTEGER NOT NULL
                )
                """;

        try (Connection connection = dataSource.getConnection();
             Statement statement = connection.createStatement()) {
            statement.executeUpdate(ledgerDdl);
            statement.executeUpdate(flagsDdl);
            if (!mysql()) {
                // MySQL declares the index inline (CREATE INDEX has no IF NOT EXISTS before 8.0.29).
                statement.executeUpdate(
                        "CREATE INDEX IF NOT EXISTS idx_ledger_uuid_created ON ledger(uuid, created_at DESC)");
            }
        }
    }

    // ------------------------------------------------------------------ writes (append-only, batched)

    /** Enqueues an audit row. Thread-safe and non-blocking; the row is persisted by the next flush. */
    public void record(MoneyEvent event) {
        writeQueue.add(new Queued(event, Instant.now().getEpochSecond()));
    }

    /** Batches queued audit rows on a pooled connection. Runs on a background thread (and on close). */
    public synchronized void flush() {
        if (dataSource == null) {
            return;
        }
        List<Queued> batch = new ArrayList<>();
        Queued queued;
        while (batch.size() < FLUSH_BATCH_CAP && (queued = writeQueue.poll()) != null) {
            batch.add(queued);
        }
        if (batch.isEmpty()) {
            return;
        }
        try (Connection connection = dataSource.getConnection()) {
            boolean previousAutoCommit = connection.getAutoCommit();
            connection.setAutoCommit(false);
            try (PreparedStatement statement = connection.prepareStatement(INSERT_SQL)) {
                for (Queued row : batch) {
                    bindInsert(statement, row.event(), row.time());
                    statement.addBatch();
                }
                statement.executeBatch();
                connection.commit();
            } catch (SQLException exception) {
                connection.rollback();
                plugin.getLogger().warning("Could not flush " + batch.size() + " ledger rows: " + exception.getMessage());
            } finally {
                connection.setAutoCommit(previousAutoCommit);
            }
        } catch (SQLException exception) {
            plugin.getLogger().warning("Ledger flush connection error: " + exception.getMessage());
        }
    }

    private void bindInsert(PreparedStatement statement, MoneyEvent event, long createdAt) throws SQLException {
        statement.setString(1, event.player().toString());
        statement.setString(2, event.playerName());
        statement.setString(3, event.source());
        statement.setString(4, event.action());
        statement.setDouble(5, event.amount());
        statement.setInt(6, event.incoming() ? 1 : 0);
        statement.setString(7, event.counterparty() == null ? null : event.counterparty().toString());
        statement.setString(8, event.counterpartyName());
        statement.setString(9, event.item());
        if (Double.isNaN(event.balanceAfter())) {
            statement.setNull(10, java.sql.Types.DOUBLE);
        } else {
            statement.setDouble(10, event.balanceAfter());
        }
        statement.setString(11, event.note());
        statement.setLong(12, createdAt);
    }

    // ------------------------------------------------------------------ reads

    public List<MoneyEvent> recent(UUID uuid, int limit) {
        flush(); // ensure just-enqueued rows are visible to history queries
        String sql = """
                SELECT uuid, username, source, action, amount, incoming, counterparty, counterparty_name,
                       item, balance_after, note
                FROM ledger WHERE uuid = ? ORDER BY created_at DESC, id DESC LIMIT ?
                """;
        List<MoneyEvent> events = new ArrayList<>();
        try (Connection connection = dataSource.getConnection();
             PreparedStatement statement = connection.prepareStatement(sql)) {
            statement.setString(1, uuid.toString());
            statement.setInt(2, Math.max(1, limit));
            try (ResultSet rs = statement.executeQuery()) {
                while (rs.next()) {
                    double balanceAfter = rs.getDouble("balance_after");
                    if (rs.wasNull()) {
                        balanceAfter = Double.NaN;
                    }
                    String counterparty = rs.getString("counterparty");
                    events.add(MoneyEvent.builder(UUID.fromString(rs.getString("uuid")), rs.getString("username"))
                            .source(rs.getString("source"))
                            .action(rs.getString("action"))
                            .amount(rs.getDouble("amount"))
                            .incoming(rs.getInt("incoming") != 0)
                            .counterparty(counterparty == null ? null : UUID.fromString(counterparty), rs.getString("counterparty_name"))
                            .item(rs.getString("item"))
                            .balanceAfter(balanceAfter)
                            .note(rs.getString("note"))
                            .build());
                }
            }
        } catch (SQLException exception) {
            plugin.getLogger().severe("Could not read ledger for " + uuid + ": " + exception.getMessage());
        }
        return events;
    }

    public long countLedger() {
        flush();
        try (Connection connection = dataSource.getConnection();
             Statement statement = connection.createStatement();
             ResultSet rs = statement.executeQuery("SELECT COUNT(*) FROM ledger")) {
            return rs.next() ? rs.getLong(1) : 0L;
        } catch (SQLException exception) {
            return -1L;
        }
    }

    // ------------------------------------------------------------------ flags

    public boolean saveFlag(Flag flag) {
        String sql = mysql() ? """
                INSERT INTO flags (uuid, username, type, reason, created_at)
                VALUES (?, ?, ?, ?, ?)
                ON DUPLICATE KEY UPDATE
                    username = VALUES(username),
                    type = VALUES(type),
                    reason = VALUES(reason),
                    created_at = VALUES(created_at)
                """ : """
                INSERT INTO flags (uuid, username, type, reason, created_at)
                VALUES (?, ?, ?, ?, ?)
                ON CONFLICT(uuid) DO UPDATE SET
                    username = excluded.username,
                    type = excluded.type,
                    reason = excluded.reason,
                    created_at = excluded.created_at
                """;
        try (Connection connection = dataSource.getConnection();
             PreparedStatement statement = connection.prepareStatement(sql)) {
            statement.setString(1, flag.player().toString());
            statement.setString(2, flag.playerName());
            statement.setString(3, flag.type());
            statement.setString(4, flag.reason());
            statement.setLong(5, flag.timestamp());
            statement.executeUpdate();
            return true;
        } catch (SQLException exception) {
            plugin.getLogger().severe("Could not save flag for " + flag.player() + ": " + exception.getMessage());
            return false;
        }
    }

    public List<Flag> getFlags() {
        String sql = "SELECT uuid, username, type, reason, created_at FROM flags ORDER BY created_at DESC";
        List<Flag> flags = new ArrayList<>();
        try (Connection connection = dataSource.getConnection();
             Statement statement = connection.createStatement();
             ResultSet rs = statement.executeQuery(sql)) {
            while (rs.next()) {
                flags.add(new Flag(
                        UUID.fromString(rs.getString("uuid")),
                        rs.getString("username"),
                        rs.getString("type"),
                        rs.getString("reason"),
                        rs.getLong("created_at")));
            }
        } catch (SQLException exception) {
            plugin.getLogger().severe("Could not load flags: " + exception.getMessage());
        }
        return flags;
    }

    public boolean isFlagged(UUID uuid) {
        try (Connection connection = dataSource.getConnection();
             PreparedStatement statement = connection.prepareStatement("SELECT 1 FROM flags WHERE uuid = ?")) {
            statement.setString(1, uuid.toString());
            try (ResultSet rs = statement.executeQuery()) {
                return rs.next();
            }
        } catch (SQLException exception) {
            return false;
        }
    }

    public boolean clearFlag(UUID uuid) {
        try (Connection connection = dataSource.getConnection();
             PreparedStatement statement = connection.prepareStatement("DELETE FROM flags WHERE uuid = ?")) {
            statement.setString(1, uuid.toString());
            return statement.executeUpdate() > 0;
        } catch (SQLException exception) {
            plugin.getLogger().severe("Could not clear flag for " + uuid + ": " + exception.getMessage());
            return false;
        }
    }

    public void clearFlags() {
        try (Connection connection = dataSource.getConnection();
             Statement statement = connection.createStatement()) {
            statement.executeUpdate("DELETE FROM flags");
        } catch (SQLException exception) {
            plugin.getLogger().severe("Could not clear flags: " + exception.getMessage());
        }
    }

    // ------------------------------------------------------------------ maintenance

    /** Prunes old ledger rows beyond the per-player cap. The window function runs on SQLite 3.25+/MySQL 8+. */
    public int prune(int maxPerPlayer) {
        if (maxPerPlayer <= 0) {
            return 0;
        }
        flush();
        String sql = """
                DELETE FROM ledger
                WHERE id NOT IN (
                    SELECT id FROM (
                        SELECT id, ROW_NUMBER() OVER (PARTITION BY uuid ORDER BY created_at DESC, id DESC) AS rn
                        FROM ledger
                    ) ranked
                    WHERE ranked.rn <= ?
                )
                """;
        try (Connection connection = dataSource.getConnection();
             PreparedStatement statement = connection.prepareStatement(sql)) {
            statement.setInt(1, maxPerPlayer);
            return statement.executeUpdate();
        } catch (SQLException exception) {
            plugin.getLogger().warning("Could not prune ledger: " + exception.getMessage());
            return 0;
        }
    }

    // synchronized so it cannot interleave with a background flush().
    public synchronized void close() {
        flush(); // drain anything still queued before shutting down
        if (dataSource == null) {
            return;
        }
        if (!mysql()) {
            try (Connection connection = dataSource.getConnection();
                 Statement statement = connection.createStatement()) {
                statement.execute("PRAGMA wal_checkpoint(TRUNCATE)");
            } catch (SQLException ignored) {
                // best effort
            }
        }
        dataSource.close();
        dataSource = null;
    }
}
