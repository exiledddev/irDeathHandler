package dev.exiledddev.deathhandler;

import dev.exiledddev.deathhandler.command.DeathbanCommand;
import dev.exiledddev.deathhandler.command.ImmortalCommand;
import dev.exiledddev.deathhandler.listener.DeathListener;
import dev.exiledddev.deathhandler.listener.ImmortalListener;
import dev.exiledddev.deathhandler.listener.LoginListener;
import dev.exiledddev.deathhandler.store.Database;
import io.papermc.paper.command.brigadier.Commands;
import io.papermc.paper.plugin.lifecycle.event.types.LifecycleEvents;
import java.io.File;
import java.sql.SQLException;
import java.util.logging.Level;
import org.bukkit.entity.Player;
import org.bukkit.plugin.PluginManager;
import org.bukkit.plugin.java.JavaPlugin;

public final class DeathHandlerPlugin extends JavaPlugin {

    private Settings settings;
    private Database database;

    @Override
    public void onEnable() {
        this.saveDefaultConfig();
        this.settings = Settings.load(this.getConfig());
        try {
            this.getDataFolder().mkdirs();
            this.database = Database.open(new File(this.getDataFolder(), "deathhandler.db"), this.getLogger());
        } catch (final SQLException e) {
            this.getLogger().log(Level.SEVERE, "Could not open the database; irDeathHandler is disabled.", e);
            this.getServer().getPluginManager().disablePlugin(this);
            return;
        }

        final DeathbanService service = new DeathbanService(this.database);
        service.load(this.getConfig().getBoolean("deathbans-enabled", true));
        // Players online after a /reload never went through login. Note their names if unknown
        // (only then: a known player may be showing a nickname right now).
        for (final Player player : this.getServer().getOnlinePlayers()) {
            if (!service.hasRealName(player.getUniqueId())) {
                service.rememberRealName(player.getUniqueId(), player.getName());
            }
        }

        final PluginManager plugins = this.getServer().getPluginManager();
        plugins.registerEvents(new LoginListener(this, service), this);
        plugins.registerEvents(new DeathListener(this, service), this);
        plugins.registerEvents(new ImmortalListener(this, service), this);

        this.getLifecycleManager().registerEventHandler(LifecycleEvents.COMMANDS, event -> {
            final Commands commands = event.registrar();
            commands.register(new DeathbanCommand(this, service).build(), "Deathban list, pardons, revives and on/off");
            commands.register(new ImmortalCommand(service).build(), "Make players immortal");
        });
        this.getLogger().info("Deathbans are " + (service.enabled() ? "ON" : "OFF") + ", " + service.bans().size() + " player(s) deathbanned.");
    }

    @Override
    public void onDisable() {
        if (this.database != null) {
            this.database.close();
        }
    }

    public Settings settings() {
        return this.settings;
    }

    public void reloadSettings() {
        this.reloadConfig();
        this.settings = Settings.load(this.getConfig());
    }
}
