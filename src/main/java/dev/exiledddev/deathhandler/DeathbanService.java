package dev.exiledddev.deathhandler;

import dev.exiledddev.deathhandler.store.Database;
import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import org.bukkit.entity.Player;
import org.jspecify.annotations.Nullable;

/**
 * Deathbans, immortal players and revives, kept in memory and saved to the database.
 *
 * <p>Everything is keyed by UUID, so nicknames (which change a player's in-game name) never let a
 * banned player back in or lose track of who is who. Names are only for showing and looking people
 * up, and both the real name and the nickname at the time of death are remembered.
 */
public final class DeathbanService {

    private static final String ENABLED = "deathbans-enabled";

    private final Database database;
    private final Map<UUID, Database.Ban> bans = new ConcurrentHashMap<>();
    private final Map<UUID, String> realNames = new ConcurrentHashMap<>();
    private final Map<UUID, String> immortals = new ConcurrentHashMap<>();
    private final Map<UUID, Database.Spot> revives = new ConcurrentHashMap<>();
    private volatile boolean enabled = true;

    public DeathbanService(final Database database) {
        this.database = database;
    }

    public void load(final boolean enabledByDefault) {
        this.bans.putAll(this.database.loadBans());
        this.realNames.putAll(this.database.loadRealNames());
        this.immortals.putAll(this.database.loadImmortals());
        this.revives.putAll(this.database.loadRevives());
        final String saved = this.database.setting(ENABLED);
        this.enabled = saved == null ? enabledByDefault : Boolean.parseBoolean(saved);
    }

    public Database database() {
        return this.database;
    }

    // ---- On/off ---------------------------------------------------------------------------

    public boolean enabled() {
        return this.enabled;
    }

    public void setEnabled(final boolean enabled) {
        this.enabled = enabled;
        this.database.setting(ENABLED, String.valueOf(enabled));
    }

    // ---- Real names -----------------------------------------------------------------------

    /** Called at login, before nickname plugins replace the player's name. */
    public void rememberRealName(final UUID uuid, final String realName) {
        if (!realName.equals(this.realNames.put(uuid, realName))) {
            this.database.saveRealName(uuid, realName);
        }
    }

    public boolean hasRealName(final UUID uuid) {
        return this.realNames.containsKey(uuid);
    }

    /** The player's real (login) name, even while they have a nickname. */
    public String realName(final Player player) {
        return this.realNames.getOrDefault(player.getUniqueId(), player.getName());
    }

    // ---- Bans -----------------------------------------------------------------------------

    public boolean isBanned(final UUID uuid) {
        return this.bans.containsKey(uuid);
    }

    public Collection<Database.Ban> bans() {
        return this.bans.values().stream().sorted((a, b) -> Long.compare(b.bannedAt(), a.bannedAt())).toList();
    }

    public void ban(final Database.Ban ban) {
        this.bans.put(ban.uuid(), ban);
        this.database.saveBan(ban);
    }

    /** Lifts a deathban. Returns the ban, or null if the player wasn't banned. */
    public Database.@Nullable Ban pardon(final UUID uuid) {
        final Database.Ban ban = this.bans.remove(uuid);
        if (ban != null) {
            this.database.deleteBan(uuid);
        }
        return ban;
    }

    public int pardonAll() {
        final int count = this.bans.size();
        this.bans.clear();
        this.database.deleteAllBans();
        return count;
    }

    // ---- Revives --------------------------------------------------------------------------

    /**
     * Pardons the player and remembers where they last died, so they reappear there next time they
     * join. Returns false if no death of theirs has been logged.
     */
    public boolean revive(final UUID uuid) {
        final List<Database.Death> deaths = this.database.deaths(uuid, 1);
        if (deaths.isEmpty()) {
            return false;
        }
        this.pardon(uuid);
        this.revives.put(uuid, deaths.getFirst().spot());
        this.database.saveRevive(uuid, deaths.getFirst().spot());
        return true;
    }

    /** The spot a revived player should come back at, removing it so it's only used once. */
    public Database.@Nullable Spot takeRevive(final UUID uuid) {
        final Database.Spot spot = this.revives.remove(uuid);
        if (spot != null) {
            this.database.deleteRevive(uuid);
        }
        return spot;
    }

    // ---- Immortals ------------------------------------------------------------------------

    public boolean isImmortal(final UUID uuid) {
        return this.immortals.containsKey(uuid);
    }

    public void setImmortal(final Player player, final boolean immortal) {
        if (immortal) {
            this.immortals.put(player.getUniqueId(), this.realName(player));
        } else {
            this.immortals.remove(player.getUniqueId());
        }
        this.database.setImmortal(player.getUniqueId(), this.realName(player), immortal);
    }

    /** Immortal players' real names. */
    public Collection<String> immortalNames() {
        return this.immortals.values().stream().sorted(String.CASE_INSENSITIVE_ORDER).toList();
    }

    // ---- Name lookups ---------------------------------------------------------------------

    /**
     * Players matching a name: a deathbanned player's real name or nickname at death, any known
     * real name, or any name a logged death was under. Ignores case.
     */
    public Set<UUID> find(final String name) {
        final Set<UUID> found = new LinkedHashSet<>();
        for (final Database.Ban ban : this.bans.values()) {
            if (ban.realName().equalsIgnoreCase(name) || ban.nickname().equalsIgnoreCase(name)) {
                found.add(ban.uuid());
            }
        }
        this.realNames.forEach((uuid, realName) -> {
            if (realName.equalsIgnoreCase(name)) {
                found.add(uuid);
            }
        });
        found.addAll(this.database.findDeathsByName(name).keySet());
        return found;
    }

    /** A display name for a UUID: the real name if known. */
    public String nameOf(final UUID uuid) {
        final Database.Ban ban = this.bans.get(uuid);
        if (ban != null) {
            return ban.realName();
        }
        return this.realNames.getOrDefault(uuid, uuid.toString());
    }

    /** Names to suggest for banned players: real names and nicknames at death. */
    public List<String> bannedNames() {
        final Set<String> names = new LinkedHashSet<>();
        for (final Database.Ban ban : this.bans()) {
            names.add(ban.realName());
            names.add(ban.nickname());
        }
        return new ArrayList<>(names);
    }

    /** Every known real name, for suggestions. */
    public List<String> knownNames() {
        return this.realNames.values().stream().distinct().sorted(String.CASE_INSENSITIVE_ORDER).toList();
    }
}
