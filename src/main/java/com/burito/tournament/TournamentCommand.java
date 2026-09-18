package com.burito.tournament;

import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import org.bukkit.Bukkit;
import org.bukkit.command.Command;
import org.bukkit.command.CommandExecutor;
import org.bukkit.command.CommandSender;
import org.bukkit.command.TabCompleter;
import org.bukkit.entity.Player;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

public class TournamentCommand implements CommandExecutor, TabCompleter {

    private final TournamentPlugin plugin;
    private final TournamentManager manager;

    public TournamentCommand(TournamentPlugin plugin, TournamentManager manager) {
        this.plugin = plugin;
        this.manager = manager;
    }

    @Override
    public boolean onCommand(CommandSender sender, Command command,
                             String label, String[] args) {

        String name = command.getName().toLowerCase(Locale.ROOT);

        if (!sender.hasPermission("tournament.admin")) {
            sender.sendMessage(Component.text("You don't have permission to do that.", NamedTextColor.RED));
            return true;
        }

        switch (name) {
            case "start" -> {
                if (manager.running()) {
                    sender.sendMessage(Component.text("A tournament is already running. Use /stop first.",
                            NamedTextColor.RED));
                    return true;
                }
                manager.start(sender);
            }
            case "stop" -> manager.hardStop();
            case "reset" -> {
                manager.resetLeaderboard();
                sender.sendMessage(Component.text("Leaderboard cleared.", NamedTextColor.GREEN));
            }
            case "setplayer1", "setplayer2", "setplayer3", "setplayer4" -> {
                if (args.length != 1) {
                    sender.sendMessage(Component.text("Usage: /" + name + " <player>", NamedTextColor.RED));
                    return true;
                }
                if (manager.running()) {
                    sender.sendMessage(Component.text("You can't change players mid-tournament. Use /stop first.",
                            NamedTextColor.RED));
                    return true;
                }
                int slot = Integer.parseInt(name.substring(name.length() - 1)) - 1;
                Player target = Bukkit.getPlayerExact(args[0]);
                String chosen = target != null ? target.getName() : args[0];
                manager.setSlot(slot, chosen);
                sender.sendMessage(Component.text("Player " + (slot + 1) + " set to ", NamedTextColor.GREEN)
                        .append(Component.text(chosen, NamedTextColor.YELLOW)));
                if (target == null) {
                    sender.sendMessage(Component.text("(That player is offline right now - they must be "
                            + "online when you run /start.)", NamedTextColor.GRAY));
                }
            }
            case "setstartborder" -> {
                if (args.length != 1) {
                    sender.sendMessage(Component.text("Usage: /setstartborder <50|100|150|200|250|300>",
                            NamedTextColor.RED));
                    return true;
                }
                int size;
                try {
                    size = Integer.parseInt(args[0]);
                } catch (NumberFormatException e) {
                    sender.sendMessage(Component.text("That's not a number.", NamedTextColor.RED));
                    return true;
                }
                if (!manager.setStartBorder(size)) {
                    sender.sendMessage(Component.text("Allowed sizes: 50, 100, 150, 200, 250, 300.",
                            NamedTextColor.RED));
                    return true;
                }
                sender.sendMessage(Component.text("Starting border set to " + size + " x " + size + ".",
                        NamedTextColor.GREEN));
            }
            case "setshrink" -> {
                if (args.length != 1 || !(args[0].equalsIgnoreCase("true") || args[0].equalsIgnoreCase("false"))) {
                    sender.sendMessage(Component.text("Usage: /setshrink <true|false>", NamedTextColor.RED));
                    return true;
                }
                boolean value = Boolean.parseBoolean(args[0]);
                manager.setShrink(value);
                sender.sendMessage(Component.text("Border shrinking " + (value ? "enabled." : "disabled."),
                        NamedTextColor.GREEN));
            }
            case "tournament" -> sendStatus(sender);
            default -> {
                return false;
            }
        }
        return true;
    }

    private void sendStatus(CommandSender sender) {
        sender.sendMessage(Component.text("--- Tournament ---", NamedTextColor.GOLD));
        sender.sendMessage(Component.text("State: " + manager.describeState(), NamedTextColor.GRAY));
        for (int i = 0; i < 4; i++) {
            String slot = manager.slot(i);
            sender.sendMessage(Component.text("Player " + (i + 1) + ": "
                    + (slot == null ? "not set" : slot), NamedTextColor.YELLOW));
        }
        sender.sendMessage(Component.text("Start border: " + manager.startBorder()
                + " | Shrinking: " + manager.shrinkEnabled(), NamedTextColor.GRAY));
        String[] p = manager.placements();
        for (int i = 0; i < 4; i++) {
            sender.sendMessage(Component.text(TournamentManager.ordinal(i + 1) + ": "
                    + (p[i] == null ? "-" : p[i]), NamedTextColor.AQUA));
        }
        sender.sendMessage(Component.text("Arena: " + fmt(manager.arena())
                + " | Hub: " + fmt(manager.hub()), NamedTextColor.DARK_GRAY));
    }

    private String fmt(org.bukkit.Location loc) {
        return (int) loc.getX() + " " + (int) loc.getY() + " " + (int) loc.getZ();
    }

    @Override
    public List<String> onTabComplete(CommandSender sender, Command command,
                                      String alias, String[] args) {
        String name = command.getName().toLowerCase(Locale.ROOT);
        List<String> out = new ArrayList<>();
        if (args.length != 1) return out;

        switch (name) {
            case "setplayer1", "setplayer2", "setplayer3", "setplayer4" -> {
                for (Player p : Bukkit.getOnlinePlayers()) {
                    if (p.getName().toLowerCase(Locale.ROOT).startsWith(args[0].toLowerCase(Locale.ROOT))) {
                        out.add(p.getName());
                    }
                }
            }
            case "setstartborder" -> {
                for (int size : TournamentManager.ALLOWED_BORDERS) {
                    if (String.valueOf(size).startsWith(args[0])) out.add(String.valueOf(size));
                }
            }
            case "setshrink" -> {
                for (String value : new String[]{"true", "false"}) {
                    if (value.startsWith(args[0].toLowerCase(Locale.ROOT))) out.add(value);
                }
            }
            default -> { }
        }
        return out;
    }
}
