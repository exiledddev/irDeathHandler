package dev.exiledddev.deathhandler.listener;

import dev.exiledddev.deathhandler.DeathbanService;
import dev.exiledddev.deathhandler.DeathHandlerPlugin;
import dev.exiledddev.deathhandler.ImmortalMath;
import org.bukkit.Material;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.entity.EntityDamageEvent;
import org.bukkit.inventory.PlayerInventory;

/**
 * /immortal: damage can't take an immortal player below the minimum health, unless they're holding
 * a totem of undying, in which case the hit goes through and the totem saves them as usual.
 */
public final class ImmortalListener implements Listener {

    private final DeathHandlerPlugin plugin;
    private final DeathbanService service;

    public ImmortalListener(final DeathHandlerPlugin plugin, final DeathbanService service) {
        this.plugin = plugin;
        this.service = service;
    }

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    public void onDamage(final EntityDamageEvent event) {
        if (!(event.getEntity() instanceof Player player) || !this.service.isImmortal(player.getUniqueId()) || holdsTotem(player)) {
            return;
        }
        final double minHealth = this.plugin.settings().minHealth();
        final double health = player.getHealth();
        if (!ImmortalMath.wouldDropBelow(health, player.getAbsorptionAmount(), event.getFinalDamage(), minHealth)) {
            return;
        }
        // Keep the hit (hurt animation, knockback) but take no damage, and settle at the minimum.
        event.setDamage(0);
        player.setHealth(ImmortalMath.protectedHealth(health, minHealth));
    }

    private static boolean holdsTotem(final Player player) {
        final PlayerInventory inventory = player.getInventory();
        return inventory.getItemInMainHand().getType() == Material.TOTEM_OF_UNDYING
            || inventory.getItemInOffHand().getType() == Material.TOTEM_OF_UNDYING;
    }
}
