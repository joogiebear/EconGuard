package com.mystipixel.econguard.config;

import org.bukkit.configuration.file.FileConfiguration;
import org.bukkit.plugin.java.JavaPlugin;

/** Sanity-checks EconGuard's config on load and warns about settings that would silently misbehave. */
public final class ConfigValidator {
    private final JavaPlugin plugin;

    public ConfigValidator(JavaPlugin plugin) {
        this.plugin = plugin;
    }

    public void validate() {
        FileConfiguration config = plugin.getConfig();

        long maxAge = config.getLong("young-account.max-age-days", 3L);
        long maxPlay = config.getLong("young-account.max-playtime-hours", 10L);
        if (maxAge <= 0 && maxPlay <= 0) {
            warn("young-account.max-age-days and max-playtime-hours are both <= 0; no account is ever 'young', "
                    + "so the velocity and young-incoming signals can never fire.");
        }

        warnIfNegative("detection.large-transaction", config.getDouble("detection.large-transaction", 100_000_000.0));
        warnIfNegative("detection.young-incoming-transfer", config.getDouble("detection.young-incoming-transfer", 50_000_000.0));
        warnIfNegative("detection.velocity.threshold", config.getDouble("detection.velocity.threshold", 250_000_000.0));
        warnIfNegative("detection.counterparty.threshold", config.getDouble("detection.counterparty.threshold", 500_000_000.0));

        if (config.getLong("detection.velocity.window-minutes", 30L) < 1) {
            warn("detection.velocity.window-minutes is < 1; it will be clamped to 1 minute.");
        }
        if (config.getLong("detection.counterparty.window-minutes", 60L) < 1) {
            warn("detection.counterparty.window-minutes is < 1; it will be clamped to 1 minute.");
        }
        if (config.getInt("database.max-rows-per-player", 500) < 0) {
            warn("database.max-rows-per-player is negative; pruning is effectively disabled.");
        }

        String webhook = config.getString("discord-webhook", "");
        if (webhook != null && !webhook.isBlank() && !webhook.startsWith("http")) {
            warn("discord-webhook does not look like a URL (should start with https://).");
        }
    }

    private void warnIfNegative(String path, double value) {
        if (value < 0.0) {
            warn(path + " is negative (" + value + "); that signal is effectively disabled.");
        }
    }

    private void warn(String message) {
        plugin.getLogger().warning("Config warning: " + message);
    }
}
