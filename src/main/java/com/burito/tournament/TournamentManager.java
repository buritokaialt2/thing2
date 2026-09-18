package com.burito.tournament;

import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import net.kyori.adventure.text.format.TextDecoration;
import net.kyori.adventure.title.Title;
import org.bukkit.Bukkit;
import org.bukkit.GameMode;
import org.bukkit.GameRule;
import org.bukkit.Location;
import org.bukkit.Sound;
import org.bukkit.World;
import org.bukkit.WorldBorder;
import org.bukkit.command.CommandSender;
import org.bukkit.configuration.file.FileConfiguration;
import org.bukkit.entity.Player;
import org.bukkit.potion.PotionEffect;
import org.bukkit.scheduler.BukkitRunnable;
import org.bukkit.scheduler.BukkitTask;
import org.bukkit.scoreboard.Criteria;
import org.bukkit.scoreboard.DisplaySlot;
import org.bukkit.scoreboard.Objective;
import org.bukkit.scoreboard.Scoreboard;
import org.bukkit.scoreboard.ScoreboardManager;

import java.time.Duration;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.UUID;

public class TournamentManager {

    public enum State { IDLE, COUNTDOWN, GRACE, LIVE, ENDING }

    private enum BorderPhase { WAIT, WARN, SHRINK, COOLDOWN }

    public static final int[] ALLOWED_BORDERS = {50, 100, 150, 200, 250, 300};

    private final TournamentPlugin plugin;

    private State state = State.IDLE;

    /** Slot 1-4, stored as names. */
    private final String[] slots = new String[4];

    private final List<UUID> participants = new ArrayList<>();
    private final List<UUID> alive = new ArrayList<>();
    private final Set<UUID> eliminated = new HashSet<>();
    /** placements[0] = 1st place, placements[3] = 4th place. */
    private final String[] placements = new String[4];

    private int startBorder = 300;
    private boolean shrinkEnabled = true;
    private int currentBorder = 300;

    private BorderPhase phase = BorderPhase.WAIT;
    private int phaseSeconds = 0;

    private final List<BukkitTask> tasks = new ArrayList<>();
    private Scoreboard board;
    private Boolean previousImmediateRespawn = null;

    public TournamentManager(TournamentPlugin plugin) {
        this.plugin = plugin;
    }

    // ------------------------------------------------------------------
    // Settings
    // ------------------------------------------------------------------

    public void loadSettings() {
        FileConfiguration c = plugin.getConfig();
        startBorder = c.getInt("start-border", 300);
        shrinkEnabled = c.getBoolean("shrink", true);
        currentBorder = startBorder;
        for (int i = 0; i < 4; i++) {
            String name = c.getString("slots." + (i + 1), "");
            slots[i] = (name == null || name.isEmpty()) ? null : name;
        }
    }

    private void saveSettings() {
        FileConfiguration c = plugin.getConfig();
        c.set("start-border", startBorder);
        c.set("shrink", shrinkEnabled);
        for (int i = 0; i < 4; i++) {
            c.set("slots." + (i + 1), slots[i] == null ? "" : slots[i]);
        }
        plugin.saveConfig();
    }

    public World world() {
        World w = Bukkit.getWorld(plugin.getConfig().getString("world", "world"));
        return w != null ? w : Bukkit.getWorlds().get(0);
    }

    private Location point(String key) {
        FileConfiguration c = plugin.getConfig();
        return new Location(world(),
                c.getDouble(key + ".x") + 0.5,
                c.getDouble(key + ".y"),
                c.getDouble(key + ".z") + 0.5);
    }

    public Location arena() { return point("arena"); }

    public Location hub() { return point("hub"); }

    public boolean goesCreative(String name) {
        for (String s : plugin.getConfig().getStringList("creative-on-elimination")) {
            if (s.equalsIgnoreCase(name)) return true;
        }
        return false;
    }

    public TournamentPlugin plugin() { return plugin; }

    public State state() { return state; }

    public boolean running() { return state != State.IDLE; }

    public int startBorder() { return startBorder; }

    public boolean shrinkEnabled() { return shrinkEnabled; }

    public String slot(int index) { return slots[index]; }

    public String[] placements() { return placements; }

    public void setSlot(int index, String name) {
        slots[index] = name;
        saveSettings();
    }

    public boolean setStartBorder(int size) {
        for (int allowed : ALLOWED_BORDERS) {
            if (allowed == size) {
                startBorder = size;
                if (!running()) currentBorder = size;
                saveSettings();
                return true;
            }
        }
        return false;
    }

    public void setShrink(boolean value) {
        shrinkEnabled = value;
        saveSettings();
    }

    // ------------------------------------------------------------------
    // Start
    // ------------------------------------------------------------------

    public boolean start(CommandSender sender) {
        for (int i = 0; i < 4; i++) {
            if (slots[i] == null) {
                sender.sendMessage(Component.text("Player slot " + (i + 1) + " is not set. Use /setplayer"
                        + (i + 1) + " <player>.", NamedTextColor.RED));
                return false;
            }
        }

        List<Player> players = new ArrayList<>();
        Set<UUID> seen = new LinkedHashSet<>();
        for (String name : slots) {
            Player p = Bukkit.getPlayerExact(name);
            if (p == null) {
                sender.sendMessage(Component.text(name + " is not online.", NamedTextColor.RED));
                return false;
            }
            if (!seen.add(p.getUniqueId())) {
                sender.sendMessage(Component.text(name + " is selected in more than one slot.", NamedTextColor.RED));
                return false;
            }
            players.add(p);
        }

        // 1. Clean slate
        cancelTasks();
        participants.clear();
        alive.clear();
        eliminated.clear();
        for (int i = 0; i < 4; i++) placements[i] = null;

        World world = world();
        if (previousImmediateRespawn == null) {
            Boolean current = world.getGameRuleValue(GameRule.DO_IMMEDIATE_RESPAWN);
            previousImmediateRespawn = current != null ? current : Boolean.FALSE;
        }
        world.setGameRule(GameRule.DO_IMMEDIATE_RESPAWN, true);

        // Border
        currentBorder = startBorder;
        WorldBorder wb = world.getWorldBorder();
        wb.setCenter(arena().getX(), arena().getZ());
        wb.setSize(currentBorder);
        wb.setWarningDistance(5);
        wb.setWarningTime(10);
        wb.setDamageAmount(0.5);
        wb.setDamageBuffer(1.0);

        // 2. Teleport players
        boolean clearInv = plugin.getConfig().getBoolean("clear-inventory-on-start", false);
        Location arena = arena();
        for (Player p : players) {
            participants.add(p.getUniqueId());
            alive.add(p.getUniqueId());
            p.teleport(arena);
            p.setGameMode(GameMode.SURVIVAL);
            p.setHealth(20.0);
            p.setFoodLevel(20);
            p.setSaturation(20f);
            p.setFireTicks(0);
            p.setFallDistance(0f);
            for (PotionEffect effect : p.getActivePotionEffects()) {
                p.removePotionEffect(effect.getType());
            }
            if (clearInv) p.getInventory().clear();
        }

        state = State.COUNTDOWN;
        updateBoard();
        broadcast(Component.text("Tournament starting: ", NamedTextColor.GOLD)
                .append(Component.text(String.join(", ", slots), NamedTextColor.YELLOW)));

        runStartCountdown();
        return true;
    }

    private void runStartCountdown() {
        final int[] n = {5};
        addTask(new BukkitRunnable() {
            @Override
            public void run() {
                if (n[0] > 0) {
                    bigTitle(Component.text(String.valueOf(n[0]), NamedTextColor.GOLD, TextDecoration.BOLD),
                            Component.empty());
                    playAll(Sound.BLOCK_NOTE_BLOCK_PLING, 1.0f);
                    n[0]--;
                } else {
                    bigTitle(Component.text("GO!", NamedTextColor.GREEN, TextDecoration.BOLD), Component.empty());
                    playAll(Sound.ENTITY_ENDER_DRAGON_GROWL, 0.7f);
                    beginGrace();
                    cancel();
                }
            }
        }.runTaskTimer(plugin, 0L, 20L));
    }

    private void beginGrace() {
        state = State.GRACE;
        int graceSeconds = plugin.getConfig().getInt("grace-seconds", 30);

        final int[] left = {graceSeconds};
        addTask(new BukkitRunnable() {
            @Override
            public void run() {
                if (left[0] > 0) {
                    actionBarAll(Component.text("PvP starts in " + left[0], NamedTextColor.YELLOW));
                    left[0]--;
                } else {
                    state = State.LIVE;
                    bigTitle(Component.text("PVP ENABLED", NamedTextColor.RED, TextDecoration.BOLD), Component.empty());
                    actionBarAll(Component.text("PvP is now enabled!", NamedTextColor.RED));
                    playAll(Sound.ENTITY_WITHER_SPAWN, 0.5f);
                    cancel();
                }
            }
        }.runTaskTimer(plugin, 0L, 20L));

        if (shrinkEnabled) startBorderTask();
    }

    // ------------------------------------------------------------------
    // Border shrinking
    // ------------------------------------------------------------------

    private void startBorderTask() {
        FileConfiguration c = plugin.getConfig();
        phase = BorderPhase.WAIT;
        phaseSeconds = c.getInt("border.first-wait", 50);

        addTask(new BukkitRunnable() {
            @Override
            public void run() {
                if (!running()) { cancel(); return; }
                if (!borderTick()) cancel();
            }
        }.runTaskTimer(plugin, 20L, 20L));
    }

    /** @return false when the border loop is finished. */
    private boolean borderTick() {
        FileConfiguration c = plugin.getConfig();
        int minimum = c.getInt("border.minimum", 50);

        switch (phase) {
            case WAIT -> {
                if (--phaseSeconds <= 0) enterWarn();
            }
            case WARN -> {
                bigTitle(Component.text(String.valueOf(phaseSeconds), NamedTextColor.RED, TextDecoration.BOLD),
                        Component.text("Border shrinking", NamedTextColor.GRAY));
                playAll(Sound.BLOCK_NOTE_BLOCK_BASS, 1.0f);
                if (--phaseSeconds <= 0) enterShrink();
            }
            case SHRINK -> {
                actionBarAll(Component.text("Border shrinking to " + currentBorder + " blocks... "
                        + phaseSeconds + "s", NamedTextColor.GOLD));
                if (--phaseSeconds <= 0) {
                    if (currentBorder <= minimum) {
                        broadcast(Component.text("The border has reached its minimum size ("
                                + minimum + "x" + minimum + ").", NamedTextColor.RED));
                        return false;
                    }
                    phase = BorderPhase.COOLDOWN;
                    phaseSeconds = c.getInt("border.cooldown", 20);
                }
            }
            case COOLDOWN -> {
                if (--phaseSeconds <= 0) {
                    if (currentBorder <= minimum) return false;
                    enterWarn();
                }
            }
        }
        return true;
    }

    private void enterWarn() {
        phase = BorderPhase.WARN;
        phaseSeconds = plugin.getConfig().getInt("border.warning", 10);
        bigTitle(Component.text("BORDER SHRINKING", NamedTextColor.RED, TextDecoration.BOLD),
                Component.text("in " + phaseSeconds + " seconds", NamedTextColor.YELLOW));
        broadcast(Component.text("BORDER SHRINKING IN " + phaseSeconds + " SECONDS", NamedTextColor.RED));
        playAll(Sound.BLOCK_NOTE_BLOCK_PLING, 1.0f);
    }

    private void enterShrink() {
        FileConfiguration c = plugin.getConfig();
        int amount = c.getInt("border.shrink-amount", 50);
        int minimum = c.getInt("border.minimum", 50);
        int time = c.getInt("border.shrink-time", 30);

        currentBorder = Math.max(minimum, currentBorder - amount);
        world().getWorldBorder().setSize(currentBorder, time);

        phase = BorderPhase.SHRINK;
        phaseSeconds = time;
        broadcast(Component.text("The border is now shrinking to " + currentBorder + " x " + currentBorder + ".",
                NamedTextColor.GOLD));
    }

    // ------------------------------------------------------------------
    // Eliminations
    // ------------------------------------------------------------------

    public boolean isParticipant(Player p) { return participants.contains(p.getUniqueId()); }

    public boolean isAlive(Player p) { return alive.contains(p.getUniqueId()); }

    public boolean isEliminated(Player p) { return eliminated.contains(p.getUniqueId()); }

    public boolean pvpAllowed() { return state == State.LIVE; }

    public void eliminate(Player p) {
        UUID id = p.getUniqueId();
        if (!alive.remove(id)) return;
        eliminated.add(id);

        int place = alive.size() + 1;          // 3 alive left -> 4th place
        placements[place - 1] = p.getName();
        updateBoard();

        broadcast(Component.text(p.getName(), NamedTextColor.YELLOW)
                .append(Component.text(" was eliminated - ", NamedTextColor.GRAY))
                .append(Component.text(ordinal(place) + " place", NamedTextColor.GOLD)));

        if (!p.isDead()) {
            sendToHub(p);
        }
        // If they died, TournamentListener sends them to the hub on respawn.

        if (alive.size() == 1) {
            Player winner = Bukkit.getPlayer(alive.get(0));
            declareWinner(winner);
        } else if (alive.isEmpty()) {
            finish();
        }
    }

    public void sendToHub(Player p) {
        p.teleport(hub());
        p.setFireTicks(0);
        p.setFallDistance(0f);
        for (PotionEffect effect : p.getActivePotionEffects()) p.removePotionEffect(effect.getType());
        p.setGameMode(goesCreative(p.getName()) ? GameMode.CREATIVE : GameMode.ADVENTURE);
        p.setHealth(20.0);
        p.setFoodLevel(20);
    }

    private void declareWinner(Player winner) {
        state = State.ENDING;
        if (winner != null) {
            placements[0] = winner.getName();
            updateBoard();
            bigTitle(Component.text("LAST MAN STANDING", NamedTextColor.GOLD, TextDecoration.BOLD),
                    Component.text(winner.getName(), NamedTextColor.YELLOW, TextDecoration.BOLD),
                    500, 4000, 1000);
            playAll(Sound.UI_TOAST_CHALLENGE_COMPLETE, 1.0f);
            broadcast(Component.text("LAST MAN STANDING: ", NamedTextColor.GOLD)
                    .append(Component.text(winner.getName(), NamedTextColor.YELLOW)));
        }

        // Wait 5 seconds, then run the return countdown.
        addTask(Bukkit.getScheduler().runTaskLater(plugin, this::runReturnCountdown, 100L));
    }

    private void runReturnCountdown() {
        final int[] left = {5};
        addTask(new BukkitRunnable() {
            @Override
            public void run() {
                if (left[0] > 0) {
                    Component line = Component.text("Returning to hub in " + left[0], NamedTextColor.AQUA);
                    actionBarAll(line);
                    for (Player p : Bukkit.getOnlinePlayers()) {
                        p.showTitle(Title.title(Component.empty(), line,
                                Title.Times.times(Duration.ZERO, Duration.ofMillis(1200), Duration.ofMillis(200))));
                    }
                    playAll(Sound.BLOCK_NOTE_BLOCK_HAT, 1.0f);
                    left[0]--;
                } else {
                    finish();
                    cancel();
                }
            }
        }.runTaskTimer(plugin, 0L, 20L));
    }

    /** Ends the tournament normally: everyone to the hub, leaderboard kept on screen. */
    private void finish() {
        cancelTasks();
        for (UUID id : participants) {
            Player p = Bukkit.getPlayer(id);
            if (p != null) sendToHub(p);
        }
        alive.clear();
        eliminated.clear();
        state = State.IDLE;
        restoreWorldSettings();
        broadcast(Component.text("Tournament finished. Use /reset to clear the leaderboard.", NamedTextColor.GRAY));
    }

    private void restoreWorldSettings() {
        World world = world();
        WorldBorder wb = world.getWorldBorder();
        wb.setSize(startBorder);
        currentBorder = startBorder;
        if (previousImmediateRespawn != null) {
            world.setGameRule(GameRule.DO_IMMEDIATE_RESPAWN, previousImmediateRespawn);
            previousImmediateRespawn = null;
        }
    }

    // ------------------------------------------------------------------
    // /reset and /stop
    // ------------------------------------------------------------------

    /** Leaderboard only. Does not stop the tournament, does not teleport anyone. */
    public void resetLeaderboard() {
        for (int i = 0; i < 4; i++) placements[i] = null;
        updateBoard();
    }

    /** Full stop: timers, border, leaderboard, placements, inventories, teleport. */
    public void hardStop() {
        cancelTasks();

        Set<UUID> everyone = new LinkedHashSet<>(participants);
        for (String name : slots) {
            if (name == null) continue;
            Player p = Bukkit.getPlayerExact(name);
            if (p != null) everyone.add(p.getUniqueId());
        }

        for (UUID id : everyone) {
            Player p = Bukkit.getPlayer(id);
            if (p == null) continue;
            p.getInventory().clear();
            p.getInventory().setArmorContents(null);
            for (PotionEffect effect : p.getActivePotionEffects()) p.removePotionEffect(effect.getType());
            p.setFireTicks(0);
            p.setFallDistance(0f);
            p.setHealth(20.0);
            p.setFoodLevel(20);
            p.teleport(hub());
            p.setGameMode(goesCreative(p.getName()) ? GameMode.CREATIVE : GameMode.ADVENTURE);
        }

        participants.clear();
        alive.clear();
        eliminated.clear();
        for (int i = 0; i < 4; i++) placements[i] = null;

        state = State.IDLE;
        restoreWorldSettings();
        updateBoard();
        broadcast(Component.text("Tournament stopped and reset.", NamedTextColor.RED));
    }

    // ------------------------------------------------------------------
    // Leaderboard
    // ------------------------------------------------------------------

    public void updateBoard() {
        if (!plugin.getConfig().getBoolean("show-leaderboard", true)) return;
        ScoreboardManager manager = Bukkit.getScoreboardManager();
        if (manager == null) return;

        board = manager.getNewScoreboard();
        Objective objective = board.registerNewObjective("tournament", Criteria.DUMMY,
                Component.text("TOURNAMENT LEADERBOARD", NamedTextColor.GOLD, TextDecoration.BOLD));
        objective.setDisplaySlot(DisplaySlot.SIDEBAR);

        String[] labels = {"§6§l1st §r§f", "§e§l2nd §r§f", "§7§l3rd §r§f", "§c§l4th §r§f"};
        for (int i = 0; i < 4; i++) {
            String name = placements[i] == null ? "§8-" : placements[i];
            objective.getScore(labels[i] + name).setScore(4 - i);
        }

        for (Player p : Bukkit.getOnlinePlayers()) p.setScoreboard(board);
    }

    public void applyBoard(Player p) {
        if (board != null) p.setScoreboard(board);
    }

    // ------------------------------------------------------------------
    // Helpers
    // ------------------------------------------------------------------

    private void addTask(BukkitTask task) { tasks.add(task); }

    public void cancelTasks() {
        for (BukkitTask t : tasks) {
            if (t != null) t.cancel();
        }
        tasks.clear();
    }

    private void bigTitle(Component main, Component sub) {
        bigTitle(main, sub, 100, 1000, 300);
    }

    private void bigTitle(Component main, Component sub, int fadeIn, int stay, int fadeOut) {
        Title title = Title.title(main, sub, Title.Times.times(
                Duration.ofMillis(fadeIn), Duration.ofMillis(stay), Duration.ofMillis(fadeOut)));
        for (Player p : Bukkit.getOnlinePlayers()) p.showTitle(title);
    }

    private void actionBarAll(Component component) {
        for (Player p : Bukkit.getOnlinePlayers()) p.sendActionBar(component);
    }

    private void playAll(Sound sound, float pitch) {
        for (Player p : Bukkit.getOnlinePlayers()) p.playSound(p.getLocation(), sound, 1.0f, pitch);
    }

    public void broadcast(Component component) {
        Bukkit.getServer().sendMessage(Component.text("[Tournament] ", NamedTextColor.DARK_AQUA).append(component));
    }

    public static String ordinal(int place) {
        return switch (place) {
            case 1 -> "1st";
            case 2 -> "2nd";
            case 3 -> "3rd";
            default -> place + "th";
        };
    }

    public String describeState() {
        return state.name().toLowerCase(Locale.ROOT);
    }
}
