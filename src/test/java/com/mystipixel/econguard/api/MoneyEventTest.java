package com.mystipixel.econguard.api;

import org.junit.jupiter.api.Test;

import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class MoneyEventTest {
    private static final double EPS = 1e-9;
    private final UUID player = UUID.randomUUID();

    @Test
    void appliesSensibleDefaults() {
        MoneyEvent event = MoneyEvent.builder(player, "Steve").amount(100).build();
        assertEquals(Sources.OTHER, event.source());
        assertEquals("transaction", event.action());
        assertTrue(event.incoming());
        assertNull(event.counterparty());
        assertFalse(event.hasCounterparty());
        assertTrue(Double.isNaN(event.balanceAfter()));
        assertEquals(100.0, event.amount(), EPS);
    }

    @Test
    void amountIsStoredAsMagnitude() {
        assertEquals(50.0, MoneyEvent.builder(player, "Steve").amount(-50).build().amount(), EPS);
    }

    @Test
    void counterpartyIsRecorded() {
        UUID other = UUID.randomUUID();
        MoneyEvent event = MoneyEvent.builder(player, "Steve").counterparty(other, "Alex").build();
        assertTrue(event.hasCounterparty());
        assertEquals(other, event.counterparty());
        assertEquals("Alex", event.counterpartyName());
    }

    @Test
    void builderChainSetsAllFields() {
        MoneyEvent event = MoneyEvent.builder(player, "Steve")
                .source(Sources.BANK)
                .action("deposit")
                .amount(10)
                .incoming(false)
                .balanceAfter(200)
                .note("note")
                .build();
        assertEquals(Sources.BANK, event.source());
        assertEquals("deposit", event.action());
        assertFalse(event.incoming());
        assertEquals(200.0, event.balanceAfter(), EPS);
        assertEquals("note", event.note());
    }
}
