package com.burito.tournament;

import org.bukkit.command.PluginCommand;
import org.bukkit.plugin.java.JavaPlugin;

public final class TournamentPlugin extends JavaPlugin {

    private TournamentManager manager;

    @Override
    public void onEnable() {
        saveDefaultConfig();

        manager = new TournamentManager(this);
        manager.loadSettings();

        TournamentCommand executor = new TournamentCommand(this, manager);
        String[] commands = {
                "start", "stop", "reset",
                "setplayer1", "setplayer2", "setplayer3", "setplayer4",
                "setstartborder", "setshrink", "tournament"
        };
        for (String name : commands) {
            PluginCommand command = getCommand(name);
            if (command != null) {
                command.setExecutor(executor);
                command.setTabCompleter(executor);
            } else {
                getLogger().warning("Command /" + name + " is missing from plugin.yml");
            }
        }

        getServer().getPluginManager().registerEvents(new TournamentListener(manager), this);
        manager.updateBoard();

        getLogger().info("PvPTournament enabled.");
    }

    @Override
    public void onDisable() {
        if (manager != null) {
            manager.cancelTasks();
        }
    }

    public TournamentManager manager() {
        return manager;
    }
}
