package dev.exiledddev.deathhandler;

import org.bukkit.configuration.file.FileConfiguration;

/**
 * A snapshot of config.yml.
 *
 * @param kickMessage   what a deathbanned player sees on the disconnect screen (MiniMessage)
 * @param forceDrops    drop the inventory and XP of deathbanned players even with keepInventory on
 * @param sound         sound played to everyone when someone is deathbanned, or empty for none
 * @param minHealth     the lowest health (in half hearts) immortal players can reach without a totem
 */
public record Settings(String kickMessage, boolean forceDrops, String sound, float soundVolume, float soundPitch, double minHealth) {

    public static Settings load(final FileConfiguration config) {
        return new Settings(
            config.getString("kick-message", "<red>You died."),
            config.getBoolean("force-drops", true),
            config.getString("sound.name", "minecraft:entity.wither.death"),
            (float) config.getDouble("sound.volume", 1.0),
            (float) config.getDouble("sound.pitch", 1.0),
            Math.clamp(config.getDouble("immortal.min-health", 2.0), 0.5, 20.0)
        );
    }
}
