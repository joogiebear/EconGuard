package com.mystipixel.econguard.service;

import com.mystipixel.econguard.util.Text;
import org.bukkit.Bukkit;
import org.bukkit.entity.Player;
import org.bukkit.plugin.java.JavaPlugin;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;

/** Routes anti-abuse alerts to the console, online staff (econguard.alerts), and an optional webhook. */
public final class Alerter {
    private final JavaPlugin plugin;
    private volatile HttpClient httpClient;

    public Alerter(JavaPlugin plugin) {
        this.plugin = plugin;
    }

    public void alert(String message) {
        plugin.getLogger().warning(Text.strip(message));
        if (plugin.getConfig().getBoolean("notify-staff", true)) {
            String formatted = Text.color(message);
            for (Player player : Bukkit.getOnlinePlayers()) {
                if (player.hasPermission("econguard.alerts")) {
                    player.sendMessage(formatted);
                }
            }
        }
        sendWebhook(Text.strip(message));
    }

    private void sendWebhook(String content) {
        String url = plugin.getConfig().getString("discord-webhook", "");
        if (url == null || url.isBlank()) {
            return;
        }
        String json = "{\"content\":\"" + jsonEscape("[EconGuard] " + content) + "\"}";
        Bukkit.getScheduler().runTaskAsynchronously(plugin, () -> {
            try {
                HttpRequest request = HttpRequest.newBuilder(URI.create(url))
                        .header("Content-Type", "application/json")
                        .POST(HttpRequest.BodyPublishers.ofString(json))
                        .build();
                httpClient().send(request, HttpResponse.BodyHandlers.discarding());
            } catch (Exception exception) {
                plugin.getLogger().warning("Could not send Discord webhook: " + exception.getMessage());
            }
        });
    }

    private HttpClient httpClient() {
        if (httpClient == null) {
            synchronized (this) {
                if (httpClient == null) {
                    httpClient = HttpClient.newHttpClient();
                }
            }
        }
        return httpClient;
    }

    private static String jsonEscape(String value) {
        StringBuilder builder = new StringBuilder(value.length() + 16);
        for (int i = 0; i < value.length(); i++) {
            char c = value.charAt(i);
            switch (c) {
                case '\\' -> builder.append("\\\\");
                case '"' -> builder.append("\\\"");
                case '\n' -> builder.append("\\n");
                case '\r' -> builder.append("\\r");
                case '\t' -> builder.append("\\t");
                default -> {
                    if (c < 0x20) {
                        builder.append(String.format("\\u%04x", (int) c));
                    } else {
                        builder.append(c);
                    }
                }
            }
        }
        return builder.toString();
    }
}
