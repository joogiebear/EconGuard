package com.mystipixel.econguard.data;

import com.mystipixel.econguard.api.Flag;
import com.mystipixel.econguard.api.MoneyEvent;
import org.bukkit.plugin.java.JavaPlugin;

import java.io.File;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Queue;
import java.util.UUID;
import java.util.concurrent.ConcurrentLinkedQueue;

/**
 * Unified SQLite ledger plus the persisted flag registry.
 *
 * Audit rows are append-only, so {@link #record} just enqueues and a background {@link #flush} batches
 * the inserts on a dedicated write connection — keeping high-volume audit writes off the main thread.
 * The flag table and all reads use a separate main-thread connection. WAL + busy_timeout make the two
 * connections to the same file safe; pruning uses a third short-lived connection.
 */
public final class Ledger {
    private static final String INSERT_SQL = """
            INSERT INTO ledger (uuid, username, source, action, amount, incoming,
                                counterparty, counterparty_name, item, balance_after, note, created_at)
            VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
            """;
    private static final int FLUSH_BATCH_CAP = 2000;

    private record Queued(MoneyEvent event, long time) {
    }

    private final JavaPlugin plugin;
    private final Queue<Queued> writeQueue = new ConcurrentLinkedQueue<>();
    private Connection connection;       // main thread: flags + reads + DDL
    private Connection writeConnection;   // background flush: ledger inserts only
    private String jdbcUrl;

    public Ledger(JavaPlugin plugin) {
        this.plugin = plugin;
    }

    public boolean connect() {
        try {
            File dataFolder = plugin.getDataFolder();
            if (!dataFolder.exists() && !dataFolder.mkdirs()) {
                plugin.getLogger().severe("Could not create data folder: " + dataFolder.getAbsolutePath());
                return false;
            }
            String fileName = plugin.getConfig().getString("database.file", "econguard.db");
            File file = new File(dataFolder, fileName);
            Class.forName("org.sqlite.JDBC");
            jdbcUrl = "jdbc:sqlite:" + file.getAbsolutePath();
            connection = open();
            createTables();
            writeConnection = open();
            return true;
        } catch (SQLException | ClassNotFoundException exception) {
            plugin.getLogger().severe("SQLite connection failed: " + exception.getMessage());
            return false;
        }
    }

    private Connection open() throws SQLException {
        Connection newConnection = DriverManager.getConnection(jdbcUrl);
        try (Statement statement = newConnection.createStatement()) {
            statement.execute("PRAGMA journal_mode=WAL");
            statement.execute("PRAGMA synchronous=NORMAL");
            statement.execute("PRAGMA busy_timeout=5000");
            statement.execute("PRAGMA cache_size=-8000");   // ~8 MB page cache
            statement.execute("PRAGMA mmap_size=67108864");  // 64 MB memory-mapped I/O
            statement.execute("PRAGMA temp_store=MEMORY");
        }
        return newConnection;
    }

    private void createTables() throws SQLException {
        try (Statement statement = connection.createStatement()) {
            statement.executeUpdate("""
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
                    """);
            statement.executeUpdate("CREATE INDEX IF NOT EXISTS idx_ledger_uuid_created ON ledger(uuid, created_at DESC)");
            statement.executeUpdate("""
                    CREATE TABLE IF NOT EXISTS flags (
                        uuid TEXT PRIMARY KEY,
                        username TEXT,
                        type TEXT NOT NULL,
                        reason TEXT NOT NULL,
                        created_at INTEGER NOT NULL
                    )
                    """);
        }
    }

    /** Enqueues an audit row. Thread-safe and non-blocking; the row is persisted by the next flush. */
    public void record(MoneyEvent event) {
        writeQueue.add(new Queued(event, Instant.now().getEpochSecond()));
    }

    /** Batches queued audit rows onto the write connection. Runs on a background thread (and on close). */
    public synchronized void flush() {
        if (writeConnection == null) {
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
        boolean previousAutoCommit = true;
        try {
            previousAutoCommit = writeConnection.getAutoCommit();
            writeConnection.setAutoCommit(false);
            try (PreparedStatement statement = writeConnection.prepareStatement(INSERT_SQL)) {
                for (Queued row : batch) {
                    bindInsert(statement, row.event(), row.time());
                    statement.addBatch();
                }
                statement.executeBatch();
            }
            writeConnection.commit();
        } catch (SQLException exception) {
            plugin.getLogger().warning("Could not flush " + batch.size() + " ledger rows: " + exception.getMessage());
            rollbackQuietly(writeConnection);
        } finally {
            restoreAutoCommit(writeConnection, previousAutoCommit);
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
            statement.setNull(10, java.sql.Types.REAL);
        } else {
            statement.setDouble(10, event.balanceAfter());
        }
        statement.setString(11, event.note());
        statement.setLong(12, createdAt);
    }

    public List<MoneyEvent> recent(UUID uuid, int limit) {
        flush(); // ensure just-enqueued rows are visible to history queries
        String sql = """
                SELECT uuid, username, source, action, amount, incoming, counterparty, counterparty_name,
                       item, balance_after, note
                FROM ledger WHERE uuid = ? ORDER BY created_at DESC, id DESC LIMIT ?
                """;
        List<MoneyEvent> events = new ArrayList<>();
        try (PreparedStatement statement = connection.prepareStatement(sql)) {
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
        try (Statement statement = connection.createStatement();
             ResultSet rs = statement.executeQuery("SELECT COUNT(*) FROM ledger")) {
            return rs.next() ? rs.getLong(1) : 0L;
        } catch (SQLException exception) {
            return -1L;
        }
    }

    public boolean saveFlag(Flag flag) {
        String sql = """
                INSERT INTO flags (uuid, username, type, reason, created_at)
                VALUES (?, ?, ?, ?, ?)
                ON CONFLICT(uuid) DO UPDATE SET
                    username = excluded.username,
                    type = excluded.type,
                    reason = excluded.reason,
                    created_at = excluded.created_at
                """;
        try (PreparedStatement statement = connection.prepareStatement(sql)) {
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
        try (Statement statement = connection.createStatement();
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
        try (PreparedStatement statement = connection.prepareStatement("SELECT 1 FROM flags WHERE uuid = ?")) {
            statement.setString(1, uuid.toString());
            try (ResultSet rs = statement.executeQuery()) {
                return rs.next();
            }
        } catch (SQLException exception) {
            return false;
        }
    }

    public boolean clearFlag(UUID uuid) {
        try (PreparedStatement statement = connection.prepareStatement("DELETE FROM flags WHERE uuid = ?")) {
            statement.setString(1, uuid.toString());
            return statement.executeUpdate() > 0;
        } catch (SQLException exception) {
            plugin.getLogger().severe("Could not clear flag for " + uuid + ": " + exception.getMessage());
            return false;
        }
    }

    public void clearFlags() {
        try (Statement statement = connection.createStatement()) {
            statement.executeUpdate("DELETE FROM flags");
        } catch (SQLException exception) {
            plugin.getLogger().severe("Could not clear flags: " + exception.getMessage());
        }
    }

    /** Prunes old ledger rows beyond the per-player cap. Uses its own connection; async-safe. */
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
        try (Connection pruneConnection = open();
             PreparedStatement statement = pruneConnection.prepareStatement(sql)) {
            statement.setInt(1, maxPerPlayer);
            return statement.executeUpdate();
        } catch (SQLException exception) {
            plugin.getLogger().warning("Could not prune ledger: " + exception.getMessage());
            return 0;
        }
    }

    // synchronized so it cannot interleave with a background flush() on the write connection.
    public synchronized void close() {
        flush(); // drain anything still queued before shutting down
        if (writeConnection != null) {
            closeQuietly(writeConnection);
            writeConnection = null;
        }
        if (connection != null) {
            try (Statement statement = connection.createStatement()) {
                statement.execute("PRAGMA wal_checkpoint(TRUNCATE)");
            } catch (SQLException ignored) {
                // best effort
            }
            closeQuietly(connection);
            connection = null;
        }
    }

    private void rollbackQuietly(Connection target) {
        try {
            target.rollback();
        } catch (SQLException exception) {
            plugin.getLogger().warning("Could not roll back ledger flush: " + exception.getMessage());
        }
    }

    private void restoreAutoCommit(Connection target, boolean value) {
        try {
            target.setAutoCommit(value);
        } catch (SQLException exception) {
            plugin.getLogger().warning("Could not restore auto-commit: " + exception.getMessage());
        }
    }

    private void closeQuietly(Connection target) {
        try {
            target.close();
        } catch (SQLException exception) {
            plugin.getLogger().warning("Could not close connection: " + exception.getMessage());
        }
    }
}
