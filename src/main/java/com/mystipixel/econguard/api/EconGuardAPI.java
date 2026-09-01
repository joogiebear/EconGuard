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

    /** Whether this player currently carries an anti-abuse flag. O(1); safe on the main thread. */
    boolean isFlagged(UUID player);

    /**
     * The pre-trade policy check: false means this player's trades should be refused right now.
     * Returns false only when the player is flagged AND {@code enforcement.block-flagged-trades} is
     * on — with enforcement off (the default) flags stay alert-only and this always permits.
     */
    boolean allowTrade(UUID player);
}
