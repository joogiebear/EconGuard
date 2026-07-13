package com.mystipixel.econguard.api;

import java.util.UUID;

/** A recorded anti-abuse flag awaiting staff review. */
public record Flag(
        UUID player,
        String playerName,
        String type,
        String reason,
        long timestamp
) {
}
