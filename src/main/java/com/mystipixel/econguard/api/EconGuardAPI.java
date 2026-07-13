package com.mystipixel.econguard.api;

import java.util.List;
import java.util.UUID;

/**
 * Public service other plugins use to report money movements and review anti-abuse flags.
 *
 * Obtain it via {@link EconGuard#get()}. All methods are safe to call from the main server thread.
 */
public interface EconGuardAPI {
    /** Record a money movement: persists it to the unified ledger and runs anti-abuse analysis. */
    void record(MoneyEvent event);

    /** Current anti-abuse flags awaiting review. */
    List<Flag> getFlags();

    /** Remove the flag(s) for a player. Returns true if anything was cleared. */
    boolean clearFlag(UUID player);

    /** Clear all flags. */
    void clearFlags();

    /** Recent ledger entries for a player, newest first. */
    List<MoneyEvent> getHistory(UUID player, int limit);
}
