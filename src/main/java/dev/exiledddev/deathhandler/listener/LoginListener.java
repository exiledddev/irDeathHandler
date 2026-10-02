package dev.exiledddev.deathhandler.listener;

import dev.exiledddev.deathhandler.DeathbanService;
import dev.exiledddev.deathhandler.DeathHandlerPlugin;
import dev.exiledddev.deathhandler.Msg;
import dev.exiledddev.deathhandler.store.Database;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.World;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.player.AsyncPlayerPreLoginEvent;
import org.bukkit.event.player.PlayerJoinEvent;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.event.player.PlayerRespawnEvent;
import org.jspecify.annotations.Nullable;

/**
 * Keeps deathbanned players out, notes everyone's real name, and brings revived players back where
 * they died.
 */
public final class LoginListener implements Listener {

    private final DeathHandlerPlugin plugin;
    private final DeathbanService service;
    /** Revived players who rejoined on the death screen; they come back at this spot when they respawn. */
    private final Map<UUID, Database.Spot> pendingRespawns = new ConcurrentHashMap<>();

    public LoginListener(final DeathHandlerPlugin plugin, final DeathbanService service) {
        this.plugin = plugin;
        this.service = service;
    }

    /**
     * Runs before nickname plugins (Rename swaps the profile at HIGH), so the profile here is still
     * the player's real one. Bans are by UUID, so a nickname can't get anyone back in.
     */
    @EventHandler(priority = EventPriority.LOWEST)
    public void onPreLogin(final AsyncPlayerPreLoginEvent event) {
        final String realName = event.getPlayerProfile().getName();
        if (realName != null) {
            this.service.rememberRealName(event.getUniqueId(), realName);
        }
        if (this.service.isBanned(event.getUniqueId())) {
            event.disallow(AsyncPlayerPreLoginEvent.Result.KICK_OTHER, Msg.MINI_MESSAGE.deserialize(this.plugin.settings().kickMessage()));
        }
    }

    @EventHandler
    public void onJoin(final PlayerJoinEvent event) {
        final Player player = event.getPlayer();
        final Database.Spot spot = this.service.takeRevive(player.getUniqueId());
        if (spot == null) {
            return;
        }
        if (player.isDead()) {
            // They were removed on the death screen and came back on it; place them when they respawn.
            this.pendingRespawns.put(player.getUniqueId(), spot);
        } else {
            Bukkit.getScheduler().runTask(this.plugin, () -> {
                final Location location = location(spot);
                if (location != null && player.isOnline()) {
                    player.teleport(location);
                }
            });
        }
    }

    @EventHandler(priority = EventPriority.HIGH)
    public void onRespawn(final PlayerRespawnEvent event) {
        final Database.Spot spot = this.pendingRespawns.remove(event.getPlayer().getUniqueId());
        final Location location = spot == null ? null : location(spot);
        if (location != null) {
            event.setRespawnLocation(location);
        }
    }

    @EventHandler
    public void onQuit(final PlayerQuitEvent event) {
        // Keep the revive for next time if they leave before respawning.
        final Database.Spot spot = this.pendingRespawns.remove(event.getPlayer().getUniqueId());
        if (spot != null) {
            this.service.database().saveRevive(event.getPlayer().getUniqueId(), spot);
        }
    }

    private static @Nullable Location location(final Database.Spot spot) {
        final World world = Bukkit.getWorld(spot.world());
        return world == null ? null : new Location(world, spot.x(), spot.y(), spot.z(), spot.yaw(), spot.pitch());
    }
}
