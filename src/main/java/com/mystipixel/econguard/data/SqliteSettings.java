package com.mystipixel.econguard.data;

import java.util.Properties;

// Pragmas as driver properties so they reach every connection; sqlite-jdbc runs only the first statement
// of connectionInitSql. No foreign_keys: the schema has none, add it together with a constraint.
final class SqliteSettings {

    // ms to wait for another connection's write lock
    static final int BUSY_TIMEOUT_MS = 5000;

    // one connection serialises writes and avoids SQLITE_BUSY; raise only with a concurrency test
    static final int POOL_SIZE = 1;

    private SqliteSettings() {
    }

    static Properties properties() {
        Properties props = new Properties();
        props.setProperty("journal_mode", "WAL");
        props.setProperty("synchronous", "NORMAL");
        props.setProperty("busy_timeout", String.valueOf(BUSY_TIMEOUT_MS));
        return props;
    }
}
