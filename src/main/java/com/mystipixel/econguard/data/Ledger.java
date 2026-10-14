package com.mystipixel.econguard.data;

import com.mystipixel.econguard.api.Flag;
import com.mystipixel.econguard.api.MoneyEvent;
import com.mystipixel.royalcommon.storage.Lease;
import com.mystipixel.royalcommon.storage.SchemaVersion;
import com.mystipixel.royalcommon.storage.ServerId;
import com.mystipixel.royalcommon.storage.TxRunner;
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
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Queue;
import java.util.UUID;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.logging.Level;

/**
 * Audit ledger and flag registry over one HikariCP pool, SQLite or MySQL. {@link #record} only enqueues;
 * a background {@link #flush} batches the inserts. The driver and pool come from Paper's library loader
 * (plugin.yml {@code libraries}); only royal-common is shaded.
 *
 * <p>Servers sharing a MySQL database share the ledger and the flags. Each server re-reads the flags
 * every {@code storage.refresh-seconds}, so a flag raised or cleared elsewhere reaches its trade checks.
 */
public final class Ledger {

    public enum Type { SQLITE, MYSQL }

    static final int SCHEMA_VERSION = 1;
    private static final String PRUNE_JOB = "prune";
    // the prune runs once at startup; a server restarting within this window leaves it to the last pruner
    private static final Duration PRUNE_LEASE = Duration.ofHours(6);

    private static final int FLUSH_BATCH_CAP = 2000;
    private static final String SQLITE_FLAGS_DDL = """
            CREATE TABLE IF NOT EXISTS %s (
                uuid TEXT NOT NULL,
                username TEXT,
                type TEXT NOT NULL,
                reason TEXT NOT NULL,
                created_at INTEGER NOT NULL,
                PRIMARY KEY (uuid, type)
            )
            """;
    private static final String INSERT_SQL = """
            INSERT INTO ledger (uuid, username, source, action, amount, incoming,
                                counterparty, counterparty_name, item, balance_after, note, created_at)
            VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
            """;

    private record Queued(MoneyEvent event, long time) {
    }

    private final JavaPlugin plugin;
    private final Queue<Queued> writeQueue = new ConcurrentLinkedQueue<>();

    // mirror of the flags table, so allowTrade is an O(1) lookup instead of a query inside another
    // plugin's transaction
    // plugin, so allowTrade is an O(1) lookup instead of a query inside another plugin's transaction.
    // Replaced whole by refreshFlags; flagChanges counts local edits so a refresh never undoes one.
    private volatile java.util.Map<UUID, java.util.Set<String>> flaggedCache = new java.util.concurrent.ConcurrentHashMap<>();
    private final java.util.concurrent.atomic.AtomicLong flagChanges = new java.util.concurrent.atomic.AtomicLong();

    private Type type;
    private HikariDataSource dataSource;
    private TxRunner db;
    private Lease lease;
    private String serverId;

    public Ledger(JavaPlugin plugin) {
        this.plugin = plugin;
    }

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
                hikari.setMaximumPoolSize(SqliteSettings.POOL_SIZE);
                // driver properties, not connectionInitSql: sqlite-jdbc runs only the first statement of that string
                hikari.setDataSourceProperties(SqliteSettings.properties());
            }

            this.serverId = ServerId.resolve(storage.getString("server-id", ""), plugin.getDataFolder().toPath());
            this.dataSource = new HikariDataSource(hikari);
            this.db = new TxRunner(dataSource);
            migrate();
            this.lease = new Lease(db, "econguard", serverId);
            lease.createTable();
            loadFlagCache();
            plugin.getLogger().info("EconGuard connected to " + type + " storage"
                    + (mysql() ? " as server " + serverId + "." : "."));
            return true;
        } catch (Exception exception) {
            plugin.getLogger().severe("EconGuard storage init failed: " + exception.getMessage());
            if (dataSource != null) {
                dataSource.close();
                dataSource = null;
            }
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

    // Servers sharing a MySQL database that start together take turns here; the second finds nothing to do.
    // Installs from before the version table start at 0 and run every step, each safe on existing tables.
    private void migrate() throws SQLException {
        int before = new SchemaVersion(db, "econguard").ensure(SCHEMA_VERSION, (connection, dialect, from, to) -> {
            if (from < 1) {
                createTables(connection);
            }
        });
        if (before < SCHEMA_VERSION) {
            plugin.getLogger().info("Database schema migrated from version " + before + " to " + SCHEMA_VERSION + ".");
        }
    }

    /** Whether several servers may be sharing this database. */
    public boolean shared() {
        return mysql();
    }

    private void createTables(Connection connection) throws SQLException {
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
                ) DEFAULT CHARSET=utf8mb4
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
                    uuid VARCHAR(36) NOT NULL,
                    username VARCHAR(32),
                    type VARCHAR(64) NOT NULL,
                    reason VARCHAR(512) NOT NULL,
                    created_at BIGINT NOT NULL,
                    PRIMARY KEY (uuid, type)
                ) DEFAULT CHARSET=utf8mb4
                """ : SQLITE_FLAGS_DDL.formatted("flags");

        try (Statement statement = connection.createStatement()) {
            statement.executeUpdate(ledgerDdl);
            statement.executeUpdate(flagsDdl);
            if (!mysql()) {
                // MySQL declares the index inline (CREATE INDEX has no IF NOT EXISTS before 8.0.29).
                statement.executeUpdate(
                        "CREATE INDEX IF NOT EXISTS idx_ledger_uuid_created ON ledger(uuid, created_at DESC)");
            }
        }
        if (mysql()) {
            convertToUtf8mb4(connection, List.of("ledger", "flags"));
        }
        keyFlagsByType(connection);
    }

    // Tables from 2026.40.0 and earlier keyed flags by uuid alone, so a second flag type overwrote the first.
    // Runs inside the migration: one transaction on SQLite, under the migration lock on MySQL.
    private void keyFlagsByType(Connection connection) throws SQLException {
        {
            int keyColumns = 0;
            try (ResultSet keys = connection.getMetaData().getPrimaryKeys(
                    mysql() ? connection.getCatalog() : null, null, "flags")) {
                while (keys.next()) {
                    keyColumns++;
                }
            }
            if (keyColumns != 1) {
                return;
            }
            plugin.getLogger().info("Updating the flags table so a player can hold more than one flag type.");
            try (Statement statement = connection.createStatement()) {
                if (mysql()) {
                    statement.executeUpdate("ALTER TABLE flags DROP PRIMARY KEY, ADD PRIMARY KEY (uuid, type)");
                    return;
                }
                statement.executeUpdate(SQLITE_FLAGS_DDL.formatted("flags_by_type"));
                statement.executeUpdate("INSERT INTO flags_by_type (uuid, username, type, reason, created_at)"
                        + " SELECT uuid, username, type, reason, created_at FROM flags");
                statement.executeUpdate("DROP TABLE flags");
                statement.executeUpdate("ALTER TABLE flags_by_type RENAME TO flags");
            }
        }
    }

    // Older tables may use latin1/utf8mb3, where a 4-byte character fails its insert and rolls back the
    // whole batch. The conversion copies the table (slow on a big ledger, runs once); failure is not fatal.
    private void convertToUtf8mb4(Connection connection, List<String> tables) {
        String query = "SELECT TABLE_NAME, TABLE_COLLATION FROM information_schema.TABLES "
                + "WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = ?";
        for (String table : tables) {
            try (PreparedStatement select = connection.prepareStatement(query)) {
                select.setString(1, table);
                try (ResultSet rows = select.executeQuery()) {
                    if (!rows.next()) {
                        continue;
                    }
                    String collation = rows.getString("TABLE_COLLATION");
                    if (collation != null && collation.toLowerCase(Locale.ROOT).startsWith("utf8mb4")) {
                        continue;
                    }
                    plugin.getLogger().info("Converting the " + table + " table from " + collation
                            + " to utf8mb4 so item names with emoji can be recorded. This runs once.");
                }
                try (Statement alter = connection.createStatement()) {
                    alter.executeUpdate("ALTER TABLE " + table + " CONVERT TO CHARACTER SET utf8mb4");
                }
            } catch (SQLException exception) {
                plugin.getLogger().log(Level.WARNING, "Could not convert the " + table + " table to utf8mb4;"
                        + " rows with emoji or other 4-byte characters may fail to save.", exception);
            }
        }
    }

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
                    events.add(readEvent(rs));
                }
            }
        } catch (SQLException exception) {
            plugin.getLogger().severe("Could not read ledger for " + uuid + ": " + exception.getMessage());
        }
        return events;
    }

    private MoneyEvent readEvent(ResultSet rs) throws SQLException {
        double balanceAfter = rs.getDouble("balance_after");
        if (rs.wasNull()) {
            balanceAfter = Double.NaN;
        }
        String counterparty = rs.getString("counterparty");
        return MoneyEvent.builder(UUID.fromString(rs.getString("uuid")), rs.getString("username"))
                .source(rs.getString("source"))
                .action(rs.getString("action"))
                .amount(rs.getDouble("amount"))
                .incoming(rs.getInt("incoming") != 0)
                .counterparty(counterparty == null ? null : UUID.fromString(counterparty), rs.getString("counterparty_name"))
                .item(rs.getString("item"))
                .balanceAfter(balanceAfter)
                .note(rs.getString("note"))
                .build();
    }

    /** The most recent ledger rows between two specific players, in either direction, newest first. */
    public List<MoneyEvent> pairRecent(UUID a, UUID b, int limit) {
        flush();
        String sql = """
                SELECT uuid, username, source, action, amount, incoming, counterparty, counterparty_name,
                       item, balance_after, note
                FROM ledger
                WHERE (uuid = ? AND counterparty = ?) OR (uuid = ? AND counterparty = ?)
                ORDER BY created_at DESC, id DESC LIMIT ?
                """;
        List<MoneyEvent> events = new ArrayList<>();
        try (Connection connection = dataSource.getConnection();
             PreparedStatement statement = connection.prepareStatement(sql)) {
            statement.setString(1, a.toString());
            statement.setString(2, b.toString());
            statement.setString(3, b.toString());
            statement.setString(4, a.toString());
            statement.setInt(5, Math.max(1, limit));
            try (ResultSet rs = statement.executeQuery()) {
                while (rs.next()) {
                    events.add(readEvent(rs));
                }
            }
        } catch (SQLException exception) {
            plugin.getLogger().severe("Could not read the pair ledger: " + exception.getMessage());
        }
        return events;
    }

    /**
     * Totals received by each side of a pair from the other, as {@code player -> {count, sum}} over
     * the incoming rows. Two entries at most; a player who never received anything is simply absent.
     */
    public java.util.Map<UUID, double[]> pairTotals(UUID a, UUID b) {
        flush();
        String sql = """
                SELECT uuid, COUNT(*) c, SUM(amount) s
                FROM ledger
                WHERE incoming = 1 AND ((uuid = ? AND counterparty = ?) OR (uuid = ? AND counterparty = ?))
                GROUP BY uuid
                """;
        java.util.Map<UUID, double[]> out = new java.util.HashMap<>();
        try (Connection connection = dataSource.getConnection();
             PreparedStatement statement = connection.prepareStatement(sql)) {
            statement.setString(1, a.toString());
            statement.setString(2, b.toString());
            statement.setString(3, b.toString());
            statement.setString(4, a.toString());
            try (ResultSet rs = statement.executeQuery()) {
                while (rs.next()) {
                    out.put(UUID.fromString(rs.getString("uuid")),
                            new double[]{rs.getLong("c"), rs.getDouble("s")});
                }
            }
        } catch (SQLException exception) {
            plugin.getLogger().severe("Could not total the pair ledger: " + exception.getMessage());
        }
        return out;
    }

    /**
     * Total received by {@code receiver} since {@code sinceEpochSecond}, from everyone or only from
     * {@code payer}. Reads the shared ledger, so it counts payments recorded on every server. Blocks on
     * the database: call it off the main thread.
     */
    public double receivedSince(UUID receiver, UUID payer, long sinceEpochSecond) {
        flush();
        String sql = "SELECT COALESCE(SUM(amount), 0) FROM ledger WHERE uuid = ? AND incoming = 1 AND created_at >= ?"
                + (payer == null ? "" : " AND counterparty = ?");
        try (Connection connection = dataSource.getConnection();
             PreparedStatement statement = connection.prepareStatement(sql)) {
            statement.setString(1, receiver.toString());
            statement.setLong(2, sinceEpochSecond);
            if (payer != null) {
                statement.setString(3, payer.toString());
            }
            try (ResultSet rs = statement.executeQuery()) {
                return rs.next() ? rs.getDouble(1) : 0.0;
            }
        } catch (SQLException exception) {
            plugin.getLogger().warning("Could not total recent payments to " + receiver + ": " + exception.getMessage());
            return 0.0;
        }
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

    private void loadFlagCache() {
        try {
            flaggedCache = readFlags();
        } catch (SQLException exception) {
            plugin.getLogger().warning("Could not preload the flag cache: " + exception.getMessage());
        }
    }

    /**
     * Re-reads the flags table, so flags raised or cleared on other servers sharing the database reach
     * this server's trade checks. Any thread. Skipped when this server changed a flag while it read: the
     * next refresh picks that up instead of undoing it.
     */
    public void refreshFlags() {
        if (dataSource == null) {
            return;
        }
        long before = flagChanges.get();
        try {
            java.util.Map<UUID, java.util.Set<String>> fresh = readFlags();
            synchronized (flagChanges) {
                if (flagChanges.get() == before) {
                    flaggedCache = fresh;
                }
            }
        } catch (SQLException exception) {
            plugin.getLogger().warning("Could not refresh flags from the database: " + exception.getMessage());
        }
    }

    private java.util.Map<UUID, java.util.Set<String>> readFlags() throws SQLException {
        java.util.Map<UUID, java.util.Set<String>> flags = new java.util.concurrent.ConcurrentHashMap<>();
        try (Connection connection = dataSource.getConnection();
             Statement statement = connection.createStatement();
             ResultSet rs = statement.executeQuery("SELECT uuid, type FROM flags")) {
            while (rs.next()) {
                try {
                    flags.computeIfAbsent(UUID.fromString(rs.getString(1)),
                            id -> java.util.concurrent.ConcurrentHashMap.newKeySet()).add(rs.getString(2));
                } catch (IllegalArgumentException ignored) {
                    // a malformed row can't veto anyone; getFlags() will surface it
                }
            }
        }
        return flags;
    }

    // every local change to the mirror goes through here, so a refresh racing it is dropped
    private void changeFlags(java.util.function.Consumer<java.util.Map<UUID, java.util.Set<String>>> change) {
        synchronized (flagChanges) {
            flagChanges.incrementAndGet();
            change.accept(flaggedCache);
        }
    }

    /** Whether this player is flagged, from the in-memory mirror. O(1), any thread. */
    public boolean isFlaggedFast(UUID uuid) {
        return flaggedCache.containsKey(uuid);
    }

    /** Whether this player holds a flag of this type, from the in-memory mirror. O(1), any thread. */
    public boolean isFlaggedFast(UUID uuid, String type) {
        java.util.Set<String> types = flaggedCache.get(uuid);
        return types != null && types.contains(type);
    }

    private void cacheFlag(UUID uuid, String type) {
        changeFlags(cache -> cache.computeIfAbsent(uuid, id -> java.util.concurrent.ConcurrentHashMap.newKeySet()).add(type));
    }

    /** How many players are currently flagged, from the in-memory mirror. */
    public int flaggedCount() {
        return flaggedCache.size();
    }

    /** How {@link #saveFlag} ended. EXISTING: the player already held this flag, e.g. raised on another server. */
    public enum FlagSave { CREATED, EXISTING, FAILED }

    /**
     * Saves a flag, or refreshes its reason and time if the player already holds that type. Only a
     * CREATED flag should be announced: with servers sharing the database, the first one to save it does.
     */
    public FlagSave saveFlag(Flag flag) {
        String update = "UPDATE flags SET username = ?, reason = ?, created_at = ? WHERE uuid = ? AND type = ?";
        try {
            boolean created = db.inTransaction(tx -> {
                Connection connection = tx.connection();
                try (PreparedStatement insert = connection.prepareStatement(tx.dialect().insertIgnore()
                        + " INTO flags (uuid, username, type, reason, created_at) VALUES (?, ?, ?, ?, ?)")) {
                    insert.setString(1, flag.player().toString());
                    insert.setString(2, flag.playerName());
                    insert.setString(3, flag.type());
                    insert.setString(4, flag.reason());
                    insert.setLong(5, flag.timestamp());
                    if (insert.executeUpdate() > 0) {
                        return true;
                    }
                }
                try (PreparedStatement statement = connection.prepareStatement(update)) {
                    statement.setString(1, flag.playerName());
                    statement.setString(2, flag.reason());
                    statement.setLong(3, flag.timestamp());
                    statement.setString(4, flag.player().toString());
                    statement.setString(5, flag.type());
                    statement.executeUpdate();
                }
                return false;
            });
            cacheFlag(flag.player(), flag.type());
            return created ? FlagSave.CREATED : FlagSave.EXISTING;
        } catch (SQLException exception) {
            plugin.getLogger().severe("Could not save flag for " + flag.player() + ": " + exception.getMessage());
            return FlagSave.FAILED;
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

    public boolean clearFlag(UUID uuid) {
        try (Connection connection = dataSource.getConnection();
             PreparedStatement statement = connection.prepareStatement("DELETE FROM flags WHERE uuid = ?")) {
            statement.setString(1, uuid.toString());
            boolean cleared = statement.executeUpdate() > 0;
            // removed even when nothing was deleted here: another server may have cleared it first
            changeFlags(cache -> cache.remove(uuid));
            return cleared;
        } catch (SQLException exception) {
            plugin.getLogger().severe("Could not clear flag for " + uuid + ": " + exception.getMessage());
            return false;
        }
    }

    public void clearFlags() {
        try (Connection connection = dataSource.getConnection();
             Statement statement = connection.createStatement()) {
            statement.executeUpdate("DELETE FROM flags");
            changeFlags(java.util.Map::clear);
        } catch (SQLException exception) {
            plugin.getLogger().severe("Could not clear flags: " + exception.getMessage());
        }
    }

    /** Prunes old ledger rows beyond the per-player cap. The window function runs on SQLite 3.25+/MySQL 8+. */
    public int prune(int maxPerPlayer) {
        if (maxPerPlayer <= 0) {
            return 0;
        }
        // servers sharing the database take turns: the first to start prunes, the rest skip until its lease ends
        try {
            if (mysql() && !lease.tryAcquireOrRenew(PRUNE_JOB, PRUNE_LEASE)) {
                return 0;
            }
        } catch (SQLException exception) {
            plugin.getLogger().warning("Could not check whether another server is pruning the ledger: "
                    + exception.getMessage());
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
        flush();
        if (dataSource == null) {
            return;
        }
        if (!mysql()) {
            try (Connection connection = dataSource.getConnection();
                 Statement statement = connection.createStatement()) {
                statement.execute("PRAGMA wal_checkpoint(TRUNCATE)");
            } catch (SQLException ignored) {
            }
        }
        if (mysql() && lease != null) {
            try {
                lease.release(PRUNE_JOB);
            } catch (SQLException ignored) {
                // the lease runs out on its own
            }
        }
        dataSource.close();
        dataSource = null;
    }
}
