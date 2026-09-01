package com.mystipixel.econguard.command;

import com.mystipixel.econguard.api.Flag;
import com.mystipixel.econguard.api.MoneyEvent;
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

    public EconGuardCommand(JavaPlugin plugin, EconGuardService service) {
        this.plugin = plugin;
        this.service = service;
    }

    @Override
    public boolean onCommand(CommandSender sender, Command command, String label, String[] args) {
        if (!sender.hasPermission("econguard.admin")) {
            send(sender, "&cYou do not have permission to use EconGuard.");
            return true;
        }
        if (args.length == 0) {
            usage(sender);
            return true;
        }
        // The three query subcommands read (and flush) the database, and history resolves an offline
        // player by name — all blocking I/O, so they run off the main thread. Paper's chat sends are
        // thread-safe, so replying from the async task is fine.
        switch (args[0].toLowerCase()) {
            case "flags" -> Bukkit.getScheduler().runTaskAsynchronously(plugin, () -> flags(sender, args));
            case "history" -> Bukkit.getScheduler().runTaskAsynchronously(plugin, () -> history(sender, args));
            case "pair" -> Bukkit.getScheduler().runTaskAsynchronously(plugin, () -> pair(sender, args));
            case "stats" -> Bukkit.getScheduler().runTaskAsynchronously(plugin, () -> stats(sender));
            case "reload" -> {
                plugin.reloadConfig();
                new com.mystipixel.econguard.config.ConfigValidator(plugin).validate();
                send(sender, "&aEconGuard config reloaded.");
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
                send(sender, cleared ? "&aCleared flag for " + args[2] + "." : "&eNo flag found for " + args[2] + ".");
            } else {
                service.clearFlags();
                send(sender, "&aCleared all flags.");
            }
            return;
        }
        List<Flag> flags = service.getFlags();
        if (flags.isEmpty()) {
            send(sender, "&aNo anti-abuse flags. All clear.");
            return;
        }
        send(sender, "&eEconGuard flags (" + flags.size() + "): &7/econguard flags clear [player]");
        long now = Instant.now().getEpochSecond();
        for (Flag flag : flags) {
            long minutesAgo = Math.max(0L, (now - flag.timestamp()) / 60L);
            send(sender, "&c• &e" + flag.playerName() + " &8[" + flag.type() + "] &7" + flag.reason() + " &8(" + minutesAgo + "m ago)");
        }
    }

    private void history(CommandSender sender, String[] args) {
        if (args.length < 2) {
            send(sender, "&cUsage: /econguard history <player> [limit]");
            return;
        }
        int limit = 10;
        if (args.length >= 3) {
            try {
                limit = Math.max(1, Math.min(50, Integer.parseInt(args[2])));
            } catch (NumberFormatException ignored) {
                send(sender, "&cLimit must be a number.");
                return;
            }
        }
        OfflinePlayer target = Bukkit.getOfflinePlayer(args[1]);
        List<MoneyEvent> history = service.getHistory(target.getUniqueId(), limit);
        if (history.isEmpty()) {
            send(sender, "&eNo ledger history for " + args[1] + ".");
            return;
        }
        String symbol = plugin.getConfig().getString("currency-symbol", "$");
        send(sender, "&eLedger for " + args[1] + " (" + history.size() + "):");
        for (MoneyEvent event : history) {
            String arrow = event.incoming() ? "&a+" : "&c-";
            String party = event.hasCounterparty() ? " &8(" + event.counterpartyName() + ")" : "";
            String item = event.item() == null ? "" : " &8[" + event.item() + "]";
            send(sender, "&7" + arrow + Text.money(event.amount(), symbol) + " &8| &f" + event.source() + "/" + event.action() + party + item);
        }
    }

    /**
     * {@code /econguard pair <a> <b> [limit]} — everything the ledger knows about one pair of
     * players: what each received from the other in total, then the recent transfers. This is the
     * question staff actually ask when the collusion signal fires, and the reason every ledger row
     * carries a counterparty.
     */
    private void pair(CommandSender sender, String[] args) {
        if (args.length < 3) {
            send(sender, "&cUsage: /econguard pair <player> <player> [limit]");
            return;
        }
        OfflinePlayer a = Bukkit.getOfflinePlayer(args[1]);
        OfflinePlayer b = Bukkit.getOfflinePlayer(args[2]);
        int limit = 10;
        if (args.length >= 4) {
            try {
                limit = Math.max(1, Math.min(50, Integer.parseInt(args[3])));
            } catch (NumberFormatException ignored) {
                send(sender, "&cLimit must be a number.");
                return;
            }
        }
        Map<UUID, double[]> totals = service.pairTotals(a.getUniqueId(), b.getUniqueId());
        List<MoneyEvent> rows = service.pairRecent(a.getUniqueId(), b.getUniqueId(), limit);
        if (totals.isEmpty() && rows.isEmpty()) {
            send(sender, "&eNo ledger entries between " + args[1] + " and " + args[2] + ".");
            return;
        }
        String symbol = plugin.getConfig().getString("currency-symbol", "$");
        send(sender, "&eLedger between &f" + args[1] + " &eand &f" + args[2] + "&e:");
        sendTotal(sender, args[1], totals.get(a.getUniqueId()), symbol);
        sendTotal(sender, args[2], totals.get(b.getUniqueId()), symbol);
        for (MoneyEvent event : rows) {
            String arrow = event.incoming() ? "&a→ " : "&c← ";
            send(sender, "&7" + arrow + "&f" + event.playerName() + " &7"
                    + event.action() + " " + Text.money(event.amount(), symbol)
                    + " &8| &f" + event.source()
                    + (event.item() == null ? "" : " &8[" + event.item() + "]"));
        }
    }

    private void sendTotal(CommandSender sender, String name, double[] total, String symbol) {
        if (total == null) {
            send(sender, "&7  " + name + " received: &fnothing");
            return;
        }
        send(sender, "&7  " + name + " received &f" + Text.money(total[1], symbol)
                + " &7in &f" + (long) total[0] + "&7 transfer(s)");
    }

    private void stats(CommandSender sender) {
        List<Flag> flags = service.getFlags();
        long ledgerRows = service.ledgerCount();
        send(sender, "&eEconGuard stats:");
        send(sender, "&7  Ledger rows: &f" + (ledgerRows < 0 ? "?" : ledgerRows));
        send(sender, "&7  Pending flags: &f" + flags.size());
        if (!flags.isEmpty()) {
            Map<String, Integer> byType = new TreeMap<>();
            for (Flag flag : flags) {
                byType.merge(flag.type(), 1, Integer::sum);
            }
            for (Map.Entry<String, Integer> entry : byType.entrySet()) {
                send(sender, "&8    - " + entry.getKey() + ": &f" + entry.getValue());
            }
        }
    }

    private void usage(CommandSender sender) {
        send(sender, "&eEconGuard commands:");
        send(sender, "&7/econguard flags [clear [player]]");
        send(sender, "&7/econguard history <player> [limit]");
        send(sender, "&7/econguard pair <player> <player> [limit]");
        send(sender, "&7/econguard stats");
        send(sender, "&7/econguard reload");
    }

    private void send(CommandSender sender, String message) {
        sender.sendMessage(Text.color(message));
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
