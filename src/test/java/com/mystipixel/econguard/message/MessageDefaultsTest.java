package com.mystipixel.econguard.message;

import org.bukkit.configuration.InvalidConfigurationException;
import org.bukkit.configuration.file.YamlConfiguration;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

// Every key the code reads must ship in messages.yml, so a server file that predates a key still shows text.
class MessageDefaultsTest {

    private static final Path SOURCES = Path.of("src", "main", "java");
    private static final Pattern MESSAGE_CALL =
            Pattern.compile("\\bmessages\\s*\\.\\s*(get|list|send|sendList)\\s*\\(\\s*(?:[\\w.()]+\\s*,\\s*)?(\"[^\"]*\"|[^,)]+)");

    @TempDir
    Path folder;

    @Test
    void everyKeyReadInCodeShipsInMessagesYml() throws IOException {
        YamlConfiguration shipped = load();
        List<String> problems = new ArrayList<>();
        int found = 0;
        for (Path file : sources()) {
            String source = Files.readString(file);
            Matcher m = MESSAGE_CALL.matcher(source);
            while (m.find()) {
                found++;
                String where = file.getFileName() + ":" + lineOf(source, m.start());
                String key = m.group(2).strip();
                if (!key.startsWith("\"")) {
                    problems.add(where + ": message key must be a string literal, not " + key);
                    continue;
                }
                key = key.substring(1, key.length() - 1);
                boolean list = m.group(1).equals("list") || m.group(1).equals("sendList");
                if (list ? !shipped.isList(key) : !shipped.isString(key)) {
                    problems.add(where + ": " + key + " is not a " + (list ? "list" : "line") + " in messages.yml");
                }
            }
        }
        assertTrue(found > 40, "found only " + found + " message reads; is the scan still matching?");
        assertTrue(problems.isEmpty(), String.join("\n", problems));
    }

    @Test
    void messagesYmlHasNoDuplicateKeys() throws IOException {
        // YAML keeps only the last of two equal keys, silently dropping the first
        List<String> duplicates = new ArrayList<>();
        Set<String> seen = new HashSet<>();
        List<String> path = new ArrayList<>();
        List<Integer> indents = new ArrayList<>();
        for (String line : resource().split("\n")) {
            String trimmed = line.strip();
            if (trimmed.isEmpty() || trimmed.startsWith("#") || trimmed.startsWith("-")) {
                continue;
            }
            int colon = trimmed.indexOf(':');
            if (colon < 0) {
                continue;
            }
            int indent = line.length() - line.stripLeading().length();
            while (!indents.isEmpty() && indents.get(indents.size() - 1) >= indent) {
                indents.remove(indents.size() - 1);
                path.remove(path.size() - 1);
            }
            path.add(trimmed.substring(0, colon));
            indents.add(indent);
            String key = String.join(".", path);
            if (!seen.add(key)) {
                duplicates.add(key);
            }
        }
        assertTrue(duplicates.isEmpty(), "duplicate keys in messages.yml: " + duplicates);
    }

    @Test
    void bundledDefaultsRenderTheTextTheCommandSentBefore() {
        MessageManager messages = TestMessages.bundled(folder.toFile());
        assertEquals("&eEconGuard flags (2): &7/econguard flags clear [player]",
                messages.get("flags.header", "count", "2"));
        assertEquals("&c• &eSteve &8[velocity] &7New account gained $5.00 within 30m &8(4m ago)",
                messages.get("flags.line", "player", "Steve", "type", "velocity",
                        "reason", "New account gained $5.00 within 30m", "minutes", "4"));
        assertEquals("&7&a+$5.00 &8| &fbank/deposit &8(Alex) &8[stone]",
                messages.get("history.line", "direction", messages.get("history.incoming"), "amount", "$5.00",
                        "source", "bank", "action", "deposit",
                        "counterparty", messages.get("history.counterparty", "name", "Alex"),
                        "item", messages.get("history.item", "item", "stone")));
        assertEquals("&7&c← &fSteve &7sent $5.00 &8| &ftrade",
                messages.get("pair.line", "direction", messages.get("pair.outgoing"), "player", "Steve",
                        "action", "sent", "amount", "$5.00", "source", "trade", "item", ""));
        assertEquals("&7  Steve received &f$5.00 &7in &f3&7 transfer(s)",
                messages.get("pair.received", "player", "Steve", "amount", "$5.00", "count", "3"));
        assertEquals("&c[EconGuard] &e4 player(s) flagged for review. &7/econguard flags",
                messages.get("alerts.join-pending", "count", "4"));
        assertEquals("&c[EconGuard] &eSteve &7sell &f$5.00 &7via auction &7(party: &fAlex&7)",
                messages.get("alerts.large-transaction", "player", "Steve", "action", "sell", "amount", "$5.00",
                        "source", "auction",
                        "party", messages.get("alerts.large-transaction-party", "counterparty", "Alex")));
        assertEquals(List.of("&eEconGuard commands:", "&7/econguard flags [clear [player]]",
                "&7/econguard history <player> [limit]", "&7/econguard pair <player> <player> [limit]",
                "&7/econguard stats", "&7/econguard reload"), messages.list("help"));
    }

    @Test
    void aPlaceholderValueIsNotExpandedAgain() {
        assertEquals("a %b% c", MessageManager.fill("a %x% c", "x", "%b%", "b", "no"));
        assertEquals("100% done", MessageManager.fill("100% done", "x", "y"));
    }

    private static List<Path> sources() throws IOException {
        try (Stream<Path> walk = Files.walk(SOURCES)) {
            return walk.filter(p -> p.toString().endsWith(".java"))
                    .filter(p -> !p.endsWith("MessageManager.java"))
                    .toList();
        }
    }

    private static int lineOf(String source, int index) {
        return (int) source.substring(0, index).chars().filter(c -> c == '\n').count() + 1;
    }

    private static String resource() throws IOException {
        try (InputStream in = MessageDefaultsTest.class.getResourceAsStream("/messages.yml")) {
            assertNotNull(in, "messages.yml");
            return new String(in.readAllBytes(), StandardCharsets.UTF_8);
        }
    }

    private static YamlConfiguration load() throws IOException {
        YamlConfiguration yaml = new YamlConfiguration();
        try {
            yaml.loadFromString(resource());
        } catch (InvalidConfigurationException e) {
            throw new IOException(e);
        }
        return yaml;
    }
}
