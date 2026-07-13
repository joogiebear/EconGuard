package com.mystipixel.econguard.api;

import java.util.UUID;

/**
 * A single money movement reported by a feature plugin (bank, auction house, /pay bridge, shop, ...).
 *
 * {@code amount} is always a positive magnitude; {@code incoming} says whether the money arrived to
 * the player (true) or left them (false). {@code counterparty} is the other party in a transfer
 * (nullable - e.g. a deposit has no counterparty). {@code balanceAfter} may be {@link Double#NaN}
 * when the source does not track a running balance.
 *
 * Build with {@link #builder(UUID, String)}.
 */
public record MoneyEvent(
        UUID player,
        String playerName,
        String source,
        String action,
        double amount,
        boolean incoming,
        UUID counterparty,
        String counterpartyName,
        String item,
        double balanceAfter,
        String note
) {
    public static Builder builder(UUID player, String playerName) {
        return new Builder(player, playerName);
    }

    public boolean hasCounterparty() {
        return counterparty != null;
    }

    public static final class Builder {
        private final UUID player;
        private final String playerName;
        private String source = Sources.OTHER;
        private String action = "transaction";
        private double amount;
        private boolean incoming = true;
        private UUID counterparty;
        private String counterpartyName;
        private String item;
        private double balanceAfter = Double.NaN;
        private String note;

        private Builder(UUID player, String playerName) {
            this.player = player;
            this.playerName = playerName;
        }

        public Builder source(String source) {
            this.source = source;
            return this;
        }

        public Builder action(String action) {
            this.action = action;
            return this;
        }

        public Builder amount(double amount) {
            this.amount = amount;
            return this;
        }

        public Builder incoming(boolean incoming) {
            this.incoming = incoming;
            return this;
        }

        public Builder counterparty(UUID counterparty, String counterpartyName) {
            this.counterparty = counterparty;
            this.counterpartyName = counterpartyName;
            return this;
        }

        public Builder item(String item) {
            this.item = item;
            return this;
        }

        public Builder balanceAfter(double balanceAfter) {
            this.balanceAfter = balanceAfter;
            return this;
        }

        public Builder note(String note) {
            this.note = note;
            return this;
        }

        public MoneyEvent build() {
            return new MoneyEvent(player, playerName, source, action, Math.abs(amount), incoming,
                    counterparty, counterpartyName, item, balanceAfter, note);
        }
    }
}
