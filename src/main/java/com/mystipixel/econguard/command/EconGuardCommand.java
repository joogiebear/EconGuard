package com.mystipixel.econguard.command;

import com.mystipixel.econguard.api.Flag;
import com.mystipixel.econguard.api.MoneyEvent;
import com.mystipixel.econguard.message.MessageManager;
import com.mystipixel.econguard.service.EconGuardService;
import com.mystipixel.econguard.util.Text;
import org.bukkit.Bukkit;
import org.bukkit.OfflinePlayer;
import org.bukkit.command.Command;
import org.bukkit.command.CommandExecutor;
import org.bukkit.command.CommandSender;
import org.bukkit.command.TabCompleter;
import org.bukkit.plugin.java.JavaPlugin;

import java.time.Instant;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import java.util.UUID;

public final class EconGuardCommand implements CommandExecutor, TabCompleter {
    private static final DateTimeFormatter TIME = DateTimeFormatter.ofPattern("MM/dd HH:mm").withZone(ZoneId.systemDefault());

    private final JavaPlugin plugin;
    private final EconGuardService service;
    private final MessageManager messages;

    public EconGuardCommand(JavaPlugin plugin, EconGuardService service, MessageManager messages) {
        this.plugin = plugin;
        this.service = service;
        this.messages = messages;
    }

    @Override
    public boolean onCommand(CommandSender sender, Command command, String label, String[] args) {
        if (!sender.hasPermission("econguard.admin")) {
            messages.send(sender, "no-permission");
            return true;
        }
        if (args.length == 0) {
            usage(sender);
            return true;
        }
        // query subcommands do blocking I/O (database, offline player by name), so they run async;
        // Paper's sendMessage is thread-safe
        switch (args[0].toLowerCase()) {
            case "flags" -> Bukkit.getScheduler().runTaskAsynchronously(plugin, () -> flags(sender, args));
            case "history" -> Bukkit.getScheduler().runTaskAsynchronously(plugin, () -> history(sender, args));
            case "pair" -> Bukkit.getScheduler().runTaskAsynchronously(plugin, () -> pair(sender, args));
            case "stats" -> Bukkit.getScheduler().runTaskAsynchronously(plugin, () -> stats(sender));
            case "reload" -> {
                plugin.reloadConfig();
                new com.mystipixel.econguard.config.ConfigValidator(plugin).validate();
                messages.reload();
                messages.send(sender, "reloaded");
            }
            default -> usage(sender);
        }
        return true;
    }

    private void flags(CommandSender sender, String[] args) {
        if (args.length >= 2 && args[1].equalsIgnoreCase("clear")) {
            if (args.length >= 3) {
                OfflinePlayer target = Bukkit.getOfflinePlayer(args[2]);
                boolean cleared = service.clearFlag(target.getUniqueId());
                if (cleared) {
                    messages.send(sender, "flags.cleared-one", "player", args[2]);
                } else {
                    messages.send(sender, "flags.not-found", "player", args[2]);
                }
            } else {
                service.clearFlags();
                messages.send(sender, "flags.cleared-all");
            }
            return;
        }
        List<Flag> flags = service.getFlags();
        if (flags.isEmpty()) {
            messages.send(sender, "flags.empty");
            return;
        }
        messages.send(sender, "flags.header", "count", String.valueOf(flags.size()));
        long now = Instant.now().getEpochSecond();
        for (Flag flag : flags) {
            long minutesAgo = Math.max(0L, (now - flag.timestamp()) / 60L);
            messages.send(sender, "flags.line", "player", flag.playerName(), "type", flag.type(),
                    "reason", flag.reason(), "minutes", String.valueOf(minutesAgo));
        }
    }

    private void history(CommandSender sender, String[] args) {
        if (args.length < 2) {
            messages.send(sender, "history.usage");
            return;
        }
        int limit = 10;
        if (args.length >= 3) {
            try {
                limit = Math.max(1, Math.min(50, Integer.parseInt(args[2])));
            } catch (NumberFormatException ignored) {
                messages.send(sender, "limit-not-number");
                return;
            }
        }
        OfflinePlayer target = Bukkit.getOfflinePlayer(args[1]);
        List<MoneyEvent> history = service.getHistory(target.getUniqueId(), limit);
        if (history.isEmpty()) {
            messages.send(sender, "history.empty", "player", args[1]);
            return;
        }
        String symbol = plugin.getConfig().getString("currency-symbol", "$");
        messages.send(sender, "history.header", "player", args[1], "count", String.valueOf(history.size()));
        for (MoneyEvent event : history) {
            String direction = event.incoming() ? messages.get("history.incoming") : messages.get("history.outgoing");
            String party = event.hasCounterparty()
                    ? messages.get("history.counterparty", "name", event.counterpartyName()) : "";
            String item = event.item() == null ? "" : messages.get("history.item", "item", event.item());
            messages.send(sender, "history.line", "direction", direction, "amount", Text.money(event.amount(), symbol),
                    "source", event.source(), "action", event.action(), "counterparty", party, "item", item);
        }
    }

    // /econguard pair <a> <b> [limit]: what each received from the other in total, then recent transfers
    private void pair(CommandSender sender, String[] args) {
        if (args.length < 3) {
            messages.send(sender, "pair.usage");
            return;
        }
        OfflinePlayer a = Bukkit.getOfflinePlayer(args[1]);
        OfflinePlayer b = Bukkit.getOfflinePlayer(args[2]);
        int limit = 10;
        if (args.length >= 4) {
            try {
                limit = Math.max(1, Math.min(50, Integer.parseInt(args[3])));
            } catch (NumberFormatException ignored) {
                messages.send(sender, "limit-not-number");
                return;
            }
        }
        Map<UUID, double[]> totals = service.pairTotals(a.getUniqueId(), b.getUniqueId());
        List<MoneyEvent> rows = service.pairRecent(a.getUniqueId(), b.getUniqueId(), limit);
        if (totals.isEmpty() && rows.isEmpty()) {
            messages.send(sender, "pair.empty", "first", args[1], "second", args[2]);
            return;
        }
        String symbol = plugin.getConfig().getString("currency-symbol", "$");
        messages.send(sender, "pair.header", "first", args[1], "second", args[2]);
        sendTotal(sender, args[1], totals.get(a.getUniqueId()), symbol);
        sendTotal(sender, args[2], totals.get(b.getUniqueId()), symbol);
        for (MoneyEvent event : rows) {
            String direction = event.incoming() ? messages.get("pair.incoming") : messages.get("pair.outgoing");
            String item = event.item() == null ? "" : messages.get("pair.item", "item", event.item());
            messages.send(sender, "pair.line", "direction", direction, "player", event.playerName(),
                    "action", event.action(), "amount", Text.money(event.amount(), symbol),
                    "source", event.source(), "item", item);
        }
    }

    private void sendTotal(CommandSender sender, String name, double[] total, String symbol) {
        if (total == null) {
            messages.send(sender, "pair.received-nothing", "player", name);
            return;
        }
        messages.send(sender, "pair.received", "player", name, "amount", Text.money(total[1], symbol),
                "count", String.valueOf((long) total[0]));
    }

    private void stats(CommandSender sender) {
        List<Flag> flags = service.getFlags();
        long ledgerRows = service.ledgerCount();
        messages.send(sender, "stats.header");
        String rows = ledgerRows < 0 ? messages.get("stats.ledger-rows-unknown") : String.valueOf(ledgerRows);
        messages.send(sender, "stats.ledger-rows", "rows", rows);
        messages.send(sender, "stats.pending-flags", "count", String.valueOf(flags.size()));
        if (!flags.isEmpty()) {
            Map<String, Integer> byType = new TreeMap<>();
            for (Flag flag : flags) {
                byType.merge(flag.type(), 1, Integer::sum);
            }
            for (Map.Entry<String, Integer> entry : byType.entrySet()) {
                messages.send(sender, "stats.type-line", "type", entry.getKey(), "count", String.valueOf(entry.getValue()));
            }
        }
    }

    private void usage(CommandSender sender) {
        messages.sendList(sender, "help");
    }

    @Override
    public List<String> onTabComplete(CommandSender sender, Command command, String alias, String[] args) {
        if (!sender.hasPermission("econguard.admin")) {
            return Collections.emptyList();
        }
        if (args.length == 1) {
            return filter(Arrays.asList("flags", "history", "pair", "stats", "reload"), args[0]);
        }
        if (args.length == 2 && args[0].equalsIgnoreCase("flags")) {
            return filter(Collections.singletonList("clear"), args[1]);
        }
        boolean nameSlot = (args.length == 2 && args[0].equalsIgnoreCase("history"))
                || ((args.length == 2 || args.length == 3) && args[0].equalsIgnoreCase("pair"));
        if (nameSlot) {
            List<String> names = new ArrayList<>();
            Bukkit.getOnlinePlayers().forEach(p -> names.add(p.getName()));
            return filter(names, args[args.length - 1]);
        }
        return Collections.emptyList();
    }

    private List<String> filter(List<String> options, String prefix) {
        String lower = prefix.toLowerCase();
        List<String> out = new ArrayList<>();
        for (String option : options) {
            if (option.toLowerCase().startsWith(lower)) {
                out.add(option);
            }
        }
        return out;
    }
}
