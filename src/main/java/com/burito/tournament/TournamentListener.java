package com.burito.tournament;

import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import org.bukkit.Bukkit;
import org.bukkit.entity.Entity;
import org.bukkit.entity.Player;
import org.bukkit.entity.Projectile;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.entity.EntityDamageByEntityEvent;
import org.bukkit.event.entity.EntityDamageEvent;
import org.bukkit.event.entity.PlayerDeathEvent;
import org.bukkit.event.player.PlayerJoinEvent;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.event.player.PlayerRespawnEvent;
import org.bukkit.projectiles.ProjectileSource;

public class TournamentListener implements Listener {

    private final TournamentManager manager;

    public TournamentListener(TournamentManager manager) {
        this.manager = manager;
    }

    /** Eliminated players sitting in the hub take no damage at all. */
    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void onDamage(EntityDamageEvent event) {
        if (event.getEntity() instanceof Player player && manager.isEliminated(player)) {
            event.setCancelled(true);
        }
    }

    /** Blocks PvP during the countdown and the 30 second grace period. */
    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void onPvp(EntityDamageByEntityEvent event) {
        if (!manager.running()) return;
        if (!(event.getEntity() instanceof Player victim)) return;

        Player attacker = resolveAttacker(event.getDamager());
        if (attacker == null) return;
        if (attacker.equals(victim)) return;

        if (manager.isEliminated(attacker)) {
            event.setCancelled(true);
            return;
        }
        if (!manager.isParticipant(victim) || !manager.isParticipant(attacker)) return;

        if (!manager.pvpAllowed()) {
            event.setCancelled(true);
            attacker.sendActionBar(Component.text("PvP is not enabled yet!", NamedTextColor.RED));
        }
    }

    private Player resolveAttacker(Entity damager) {
        if (damager instanceof Player player) return player;
        if (damager instanceof Projectile projectile) {
            ProjectileSource source = projectile.getShooter();
            if (source instanceof Player shooter) return shooter;
        }
        return null;
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onDeath(PlayerDeathEvent event) {
        Player player = event.getEntity();
        if (!manager.running() || !manager.isAlive(player)) return;

        if (manager.plugin().getConfig().getBoolean("clear-drops-on-death", true)) {
            event.getDrops().clear();
            event.setDroppedExp(0);
        }
        manager.eliminate(player);
    }

    @EventHandler
    public void onRespawn(PlayerRespawnEvent event) {
        Player player = event.getPlayer();
        if (!manager.isEliminated(player)) return;

        event.setRespawnLocation(manager.hub());
        Bukkit.getScheduler().runTask(manager.plugin(), () -> manager.sendToHub(player));
    }

    @EventHandler
    public void onJoin(PlayerJoinEvent event) {
        manager.applyBoard(event.getPlayer());
    }

    @EventHandler
    public void onQuit(PlayerQuitEvent event) {
        Player player = event.getPlayer();
        if (manager.running() && manager.isAlive(player)) {
            manager.broadcast(Component.text(player.getName() + " left the tournament.", NamedTextColor.GRAY));
            manager.eliminate(player);
        }
    }
}
