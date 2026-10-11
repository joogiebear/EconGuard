package com.mystipixel.econguard.message;

import com.mystipixel.econguard.util.Text;
import org.bukkit.command.CommandSender;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.plugin.java.JavaPlugin;

import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;

/**
 * Player-facing text from {@code messages.yml}. The jar's copy backs every missing key; blank text
 * silences a chat message.
 */
public final class MessageManager {

    private final JavaPlugin plugin;
    private volatile YamlConfiguration messages;

    public MessageManager(JavaPlugin plugin) {
        this.plugin = plugin;
        reload();
    }

    public void reload() {
        File file = new File(plugin.getDataFolder(), "messages.yml");
        if (!file.exists()) {
            plugin.saveResource("messages.yml", false);
        }
        YamlConfiguration loaded = YamlConfiguration.loadConfiguration(file);
        try (InputStream in = plugin.getResource("messages.yml")) {
            if (in != null) {
                loaded.setDefaults(YamlConfiguration.loadConfiguration(new InputStreamReader(in, StandardCharsets.UTF_8)));
            }
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
        this.messages = loaded;
    }

    /** One line, with {@code %name%} placeholders given as alternating name/value pairs (names without %). */
    public String get(String key, String... replacements) {
        return fill(messages.getString(key), replacements);
    }

    public List<String> list(String key, String... replacements) {
        List<String> out = new ArrayList<>();
        for (String line : messages.getStringList(key)) {
            out.add(fill(line, replacements));
        }
        return out;
    }

    public void send(CommandSender target, String key, String... replacements) {
        String raw = get(key, replacements);
        if (!raw.isBlank()) {
            target.sendMessage(Text.color(raw));
        }
    }

    public void sendList(CommandSender target, String key, String... replacements) {
        for (String line : list(key, replacements)) {
            target.sendMessage(Text.color(line));
        }
    }

    // one pass, so a value that itself contains %name% is never expanded again
    static String fill(String raw, String... replacements) {
        if (raw == null) {
            return "";
        }
        if (replacements.length == 0) {
            return raw;
        }
        StringBuilder out = new StringBuilder(raw.length() + 16);
        int i = 0;
        while (true) {
            int start = raw.indexOf('%', i);
            int end = start < 0 ? -1 : raw.indexOf('%', start + 1);
            if (end < 0) {
                break;
            }
            int index = indexOf(raw.substring(start + 1, end), replacements);
            if (index < 0) {
                out.append(raw, i, end);
                i = end;
            } else {
                out.append(raw, i, start).append(replacements[index + 1]);
                i = end + 1;
            }
        }
        return out.append(raw, i, raw.length()).toString();
    }

    private static int indexOf(String name, String... replacements) {
        for (int i = 0; i + 1 < replacements.length; i += 2) {
            if (replacements[i].equals(name)) {
                return i;
            }
        }
        return -1;
    }
}
