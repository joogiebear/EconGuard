package com.mystipixel.econguard.api;

import org.bukkit.Bukkit;
import org.bukkit.plugin.RegisteredServiceProvider;

import java.util.Optional;

/**
 * Convenience accessor for the {@link EconGuardAPI} service.
 *
 * Feature plugins should depend on EconGuard as a soft-dependency and report events like:
 * <pre>{@code
 * EconGuard.get().ifPresent(api -> api.record(
 *     MoneyEvent.builder(uuid, name).source(Sources.BANK).action("deposit")
 *               .amount(amount).incoming(false).build()));
 * }</pre>
 */
public final class EconGuard {
    private EconGuard() {
    }

    public static Optional<EconGuardAPI> get() {
        RegisteredServiceProvider<EconGuardAPI> provider =
                Bukkit.getServicesManager().getRegistration(EconGuardAPI.class);
        return provider == null ? Optional.empty() : Optional.ofNullable(provider.getProvider());
    }
}
