package dev.exiledddev.deathhandler.listener;

import dev.exiledddev.deathhandler.DeathbanService;
import dev.exiledddev.deathhandler.DeathHandlerPlugin;
import dev.exiledddev.deathhandler.Msg;
import dev.exiledddev.deathhandler.Permissions;
import dev.exiledddev.deathhandler.Settings;
import dev.exiledddev.deathhandler.store.Database;
import java.util.Objects;
import java.util.stream.Stream;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.serializer.plain.PlainTextComponentSerializer;
import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.SoundCategory;
import org.bukkit.attribute.Attribute;
import org.bukkit.attribute.AttributeInstance;
import org.bukkit.entity.Entity;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.entity.PlayerDeathEvent;
import org.bukkit.event.player.PlayerKickEvent;
import org.bukkit.inventory.ItemStack;
import org.jspecify.annotations.Nullable;

/**
 * Turns deaths into deathbans: the loot drops, everyone hears the wither die, and the player is
 * removed with an ordinary "left the game" message.
 */
public final class DeathListener implements Listener {

    private final DeathHandlerPlugin plugin;
    private final DeathbanService service;

    public DeathListener(final DeathHandlerPlugin plugin, final DeathbanService service) {
        this.plugin = plugin;
        this.service = service;
    }

    /** Immortal players never die: if something got past the damage check, cancel the death. */
    @EventHandler(priority = EventPriority.LOWEST)
    public void onImmortalDeath(final PlayerDeathEvent event) {
        if (this.service.isImmortal(event.getPlayer().getUniqueId())) {
            final AttributeInstance maxHealth = event.getPlayer().getAttribute(Attribute.MAX_HEALTH);
            event.setReviveHealth(Math.min(this.plugin.settings().minHealth(), maxHealth == null ? 20.0 : maxHealth.getValue()));
            event.setCancelled(true);
        }
    }

    /**
     * Runs after other plugins (Rename scrambles obscured nicknames at HIGH), so the logged death
     * message is the final one.
     */
    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    public void onDeath(final PlayerDeathEvent event) {
        final Player player = event.getPlayer();
        final boolean deathban = this.service.enabled() && !player.hasPermission(Permissions.EXEMPT);
        final Settings settings = this.plugin.settings();

        if (deathban && settings.forceDrops()) {
            forceDrops(event, player);
        }

        final String realName = this.service.realName(player);
        final String nickname = player.getName();
        final String cause = event.deathMessage() == null ? nickname + " died" : PlainTextComponentSerializer.plainText().serialize(event.deathMessage());
        final Location at = player.getLocation();
        final long now = System.currentTimeMillis();
        this.service.database().logDeath(new Database.Death(player.getUniqueId(), realName, nickname, cause, killer(event),
            new Database.Spot(at.getWorld().getName(), at.getX(), at.getY(), at.getZ(), at.getYaw(), at.getPitch()), now, deathban));

        if (!deathban) {
            return;
        }
        this.service.ban(new Database.Ban(player.getUniqueId(), realName, nickname, cause, now));
        this.playSound(settings);

        // Remove them right after the death finishes (loot drops during the event). The kick keeps
        // the normal yellow "<name> left the game" message, which shows their nickname.
        final Component reason = Msg.MINI_MESSAGE.deserialize(settings.kickMessage());
        Bukkit.getScheduler().runTask(this.plugin, () -> {
            if (player.isOnline()) {
                player.kick(reason, PlayerKickEvent.Cause.PLUGIN);
            }
        });
    }

    /** With keepInventory on, nothing would drop; drop the inventory and XP anyway, like vanilla. */
    private static void forceDrops(final PlayerDeathEvent event, final Player player) {
        if (event.getKeepInventory()) {
            event.setKeepInventory(false);
            event.getItemsToKeep().clear();
            event.getDrops().clear();
            Stream.of(player.getInventory().getContents())
                .filter(Objects::nonNull)
                .filter(item -> !item.isEmpty())
                .map(ItemStack::clone)
                .forEach(event.getDrops()::add);
        }
        if (event.getKeepLevel()) {
            event.setKeepLevel(false);
            event.setDroppedExp(Math.min(player.getLevel() * 7, 100));
            event.setShouldDropExperience(true);
        }
    }

    private static @Nullable String killer(final PlayerDeathEvent event) {
        final Entity causing = event.getDamageSource().getCausingEntity();
        if (causing == null || causing.equals(event.getPlayer())) {
            return null;
        }
        return causing instanceof Player killer ? killer.getName() : PlainTextComponentSerializer.plainText().serialize(causing.name());
    }

    /** The wither death sound (or whatever's configured) for everyone, at their own position. */
    private void playSound(final Settings settings) {
        if (settings.sound().isBlank()) {
            return;
        }
        for (final Player listener : Bukkit.getOnlinePlayers()) {
            listener.playSound(listener.getLocation(), settings.sound(), SoundCategory.MASTER, settings.soundVolume(), settings.soundPitch());
        }
    }
}
