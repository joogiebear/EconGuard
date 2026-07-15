package com.mystipixel.econguard.api;

import org.bukkit.Bukkit;
import org.bukkit.plugin.RegisteredServiceProvider;

import java.util.UUID;
import java.util.Optional;

/**
 * Convenience accessor for the {@link EconGuardAPI} service.
 *
 * <p>Feature plugins that compile against EconGuard can use the typed builder:
 * <pre>{@code
 * EconGuard.get().ifPresent(api -> api.record(
 *     MoneyEvent.builder(uuid, name).source(Sources.BANK).action("deposit")
 *               .amount(amount).incoming(false).build()));
 * }</pre>
 *
 * <p>Plugins that would rather not take a build-time dependency on EconGuard can instead call the
 * flat {@link #record(UUID, String, String, String, double, boolean, double, UUID, String, String, String)}
 * bridge by reflection - a single method invocation with only JDK types in its signature:
 * <pre>{@code
 * Class<?> eg = Class.forName("com.mystipixel.econguard.api.EconGuard");
 * eg.getMethod("record", UUID.class, String.class, String.class, String.class, double.class,
 *              boolean.class, double.class, UUID.class, String.class, String.class, String.class)
 *   .invoke(null, uuid, name, "bank", "deposit", amount, false, balanceAfter, null, null, null, null);
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

    /**
     * Flat, reflection-friendly recorder. Builds a {@link MoneyEvent} and records it if the service is
     * available; a no-op otherwise. Its signature uses only JDK types so feature plugins can invoke it
     * reflectively without compiling against EconGuard. Pass {@link Double#NaN} for {@code balanceAfter}
     * when the source has no running balance, and {@code null} for any unused reference argument.
     */
    public static void record(UUID player, String playerName, String source, String action,
                              double amount, boolean incoming, double balanceAfter,
                              UUID counterparty, String counterpartyName, String item, String note) {
        get().ifPresent(api -> {
            MoneyEvent.Builder builder = MoneyEvent.builder(player, playerName)
                    .source(source == null ? Sources.OTHER : source)
                    .action(action == null ? "transaction" : action)
                    .amount(amount)
                    .incoming(incoming)
                    .balanceAfter(balanceAfter);
            if (counterparty != null) {
                builder.counterparty(counterparty, counterpartyName);
            }
            if (item != null) {
                builder.item(item);
            }
            if (note != null) {
                builder.note(note);
            }
            api.record(builder.build());
        });
    }
}
