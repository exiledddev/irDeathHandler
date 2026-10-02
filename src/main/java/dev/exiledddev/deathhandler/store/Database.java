package dev.exiledddev.deathhandler.store;

import java.io.File;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.logging.Level;
import java.util.logging.Logger;
import org.jspecify.annotations.Nullable;

/**
 * Everything irDeathHandler remembers, in a single SQLite file ({@code deathhandler.db}):
 * <ul>
 *   <li>{@code players}: each player's real name, captured at login before nickname plugins swap it</li>
 *   <li>{@code bans}: active deathbans</li>
 *   <li>{@code deaths}: every death, kept even after pardons</li>
 *   <li>{@code immortals}: players with /immortal on</li>
 *   <li>{@code revives}: pardoned players to put back where they died when they next join</li>
 *   <li>{@code settings}: small saved values such as the on/off switch</li>
 * </ul>
 * Methods are synchronized because they're called from the main thread and from async login.
 */
public final class Database {

    /** A place in a world. */
    public record Spot(String world, double x, double y, double z, float yaw, float pitch) {
    }

    /** An active deathban. */
    public record Ban(UUID uuid, String realName, String nickname, String cause, long bannedAt) {
    }

    /** A logged death. */
    public record Death(UUID uuid, String realName, String nickname, String cause, @Nullable String killer, Spot spot, long diedAt, boolean banned) {
    }

    private final Logger logger;
    private final Connection connection;

    private Database(final Connection connection, final Logger logger) {
        this.connection = connection;
        this.logger = logger;
    }

    public static Database open(final File file, final Logger logger) throws SQLException {
        try {
            Class.forName("org.sqlite.JDBC");
        } catch (final ClassNotFoundException e) {
            throw new SQLException("The SQLite driver is missing from this server", e);
        }
        final Database database = new Database(DriverManager.getConnection("jdbc:sqlite:" + file.getAbsolutePath()), logger);
        database.createTables();
        return database;
    }

    private void createTables() throws SQLException {
        final String spot = "world VARCHAR(64) NOT NULL, x DOUBLE NOT NULL, y DOUBLE NOT NULL, z DOUBLE NOT NULL, yaw FLOAT NOT NULL, pitch FLOAT NOT NULL";
        try (Statement statement = this.connection.createStatement()) {
            statement.executeUpdate("CREATE TABLE IF NOT EXISTS players (uuid VARCHAR(36) PRIMARY KEY, real_name VARCHAR(16) NOT NULL, updated_at BIGINT NOT NULL)");
            statement.executeUpdate("CREATE TABLE IF NOT EXISTS bans (uuid VARCHAR(36) PRIMARY KEY, real_name VARCHAR(16) NOT NULL, "
                + "nickname VARCHAR(16) NOT NULL, cause TEXT NOT NULL, banned_at BIGINT NOT NULL)");
            statement.executeUpdate("CREATE TABLE IF NOT EXISTS deaths (id INTEGER PRIMARY KEY AUTOINCREMENT, uuid VARCHAR(36) NOT NULL, "
                + "real_name VARCHAR(16) NOT NULL, nickname VARCHAR(16) NOT NULL, cause TEXT NOT NULL, killer VARCHAR(64), "
                + spot + ", died_at BIGINT NOT NULL, banned INTEGER NOT NULL)");
            statement.executeUpdate("CREATE INDEX IF NOT EXISTS idx_deaths_uuid ON deaths (uuid)");
            statement.executeUpdate("CREATE TABLE IF NOT EXISTS immortals (uuid VARCHAR(36) PRIMARY KEY, name VARCHAR(16) NOT NULL)");
            statement.executeUpdate("CREATE TABLE IF NOT EXISTS revives (uuid VARCHAR(36) PRIMARY KEY, " + spot + ")");
            statement.executeUpdate("CREATE TABLE IF NOT EXISTS settings (name VARCHAR(64) PRIMARY KEY, value TEXT)");
        }
    }

    public synchronized void close() {
        try {
            this.connection.close();
        } catch (final SQLException e) {
            this.logger.log(Level.WARNING, "Could not close the database", e);
        }
    }

    // ---- Real names -----------------------------------------------------------------------

    public synchronized void saveRealName(final UUID uuid, final String realName) {
        this.update("save " + realName, "DELETE FROM players WHERE uuid = ?", uuid.toString());
        this.update("save " + realName, "INSERT INTO players (uuid, real_name, updated_at) VALUES (?, ?, ?)", uuid.toString(), realName, System.currentTimeMillis());
    }

    public synchronized Map<UUID, String> loadRealNames() {
        final Map<UUID, String> names = new LinkedHashMap<>();
        this.query("load player names", "SELECT uuid, real_name FROM players", rows -> names.put(UUID.fromString(rows.getString(1)), rows.getString(2)));
        return names;
    }

    // ---- Bans -----------------------------------------------------------------------------

    public synchronized Map<UUID, Ban> loadBans() {
        final Map<UUID, Ban> bans = new LinkedHashMap<>();
        this.query("load deathbans", "SELECT uuid, real_name, nickname, cause, banned_at FROM bans ORDER BY banned_at", rows -> {
            final UUID uuid = UUID.fromString(rows.getString(1));
            bans.put(uuid, new Ban(uuid, rows.getString(2), rows.getString(3), rows.getString(4), rows.getLong(5)));
        });
        return bans;
    }

    public synchronized void saveBan(final Ban ban) {
        this.update("save deathban", "DELETE FROM bans WHERE uuid = ?", ban.uuid().toString());
        this.update("save deathban", "INSERT INTO bans (uuid, real_name, nickname, cause, banned_at) VALUES (?, ?, ?, ?, ?)",
            ban.uuid().toString(), ban.realName(), ban.nickname(), ban.cause(), ban.bannedAt());
    }

    public synchronized void deleteBan(final UUID uuid) {
        this.update("pardon", "DELETE FROM bans WHERE uuid = ?", uuid.toString());
    }

    public synchronized void deleteAllBans() {
        this.update("pardon everyone", "DELETE FROM bans");
    }

    // ---- Death log ------------------------------------------------------------------------

    public synchronized void logDeath(final Death death) {
        final Spot spot = death.spot();
        this.update("log death", "INSERT INTO deaths (uuid, real_name, nickname, cause, killer, world, x, y, z, yaw, pitch, died_at, banned) "
                + "VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)",
            death.uuid().toString(), death.realName(), death.nickname(), death.cause(), death.killer(),
            spot.world(), spot.x(), spot.y(), spot.z(), spot.yaw(), spot.pitch(), death.diedAt(), death.banned() ? 1 : 0);
    }

    /** A player's deaths, newest first. */
    public synchronized List<Death> deaths(final UUID uuid, final int limit) {
        final List<Death> deaths = new ArrayList<>();
        this.query("read deaths", "SELECT uuid, real_name, nickname, cause, killer, world, x, y, z, yaw, pitch, died_at, banned FROM deaths "
            + "WHERE uuid = ? ORDER BY died_at DESC LIMIT " + limit, rows -> deaths.add(new Death(
                UUID.fromString(rows.getString(1)), rows.getString(2), rows.getString(3), rows.getString(4), rows.getString(5),
                new Spot(rows.getString(6), rows.getDouble(7), rows.getDouble(8), rows.getDouble(9), rows.getFloat(10), rows.getFloat(11)),
                rows.getLong(12), rows.getInt(13) != 0)), uuid.toString());
        return deaths;
    }

    /** Players (UUID and the names they died under) whose death was logged with this real name or nickname. */
    public synchronized Map<UUID, String> findDeathsByName(final String name) {
        final Map<UUID, String> found = new LinkedHashMap<>();
        this.query("find deaths", "SELECT uuid, real_name FROM deaths WHERE LOWER(real_name) = LOWER(?) OR LOWER(nickname) = LOWER(?) ORDER BY died_at DESC",
            rows -> found.putIfAbsent(UUID.fromString(rows.getString(1)), rows.getString(2)), name, name);
        return found;
    }

    // ---- Immortals ------------------------------------------------------------------------

    public synchronized Map<UUID, String> loadImmortals() {
        final Map<UUID, String> immortals = new LinkedHashMap<>();
        this.query("load immortals", "SELECT uuid, name FROM immortals", rows -> immortals.put(UUID.fromString(rows.getString(1)), rows.getString(2)));
        return immortals;
    }

    public synchronized void setImmortal(final UUID uuid, final String name, final boolean immortal) {
        this.update("save immortal", "DELETE FROM immortals WHERE uuid = ?", uuid.toString());
        if (immortal) {
            this.update("save immortal", "INSERT INTO immortals (uuid, name) VALUES (?, ?)", uuid.toString(), name);
        }
    }

    // ---- Revives --------------------------------------------------------------------------

    public synchronized Map<UUID, Spot> loadRevives() {
        final Map<UUID, Spot> revives = new LinkedHashMap<>();
        this.query("load revives", "SELECT uuid, world, x, y, z, yaw, pitch FROM revives", rows -> revives.put(UUID.fromString(rows.getString(1)),
            new Spot(rows.getString(2), rows.getDouble(3), rows.getDouble(4), rows.getDouble(5), rows.getFloat(6), rows.getFloat(7))));
        return revives;
    }

    public synchronized void saveRevive(final UUID uuid, final Spot spot) {
        this.update("save revive", "DELETE FROM revives WHERE uuid = ?", uuid.toString());
        this.update("save revive", "INSERT INTO revives (uuid, world, x, y, z, yaw, pitch) VALUES (?, ?, ?, ?, ?, ?, ?)",
            uuid.toString(), spot.world(), spot.x(), spot.y(), spot.z(), spot.yaw(), spot.pitch());
    }

    public synchronized void deleteRevive(final UUID uuid) {
        this.update("clear revive", "DELETE FROM revives WHERE uuid = ?", uuid.toString());
    }

    // ---- Settings -------------------------------------------------------------------------

    public synchronized @Nullable String setting(final String name) {
        final List<String> values = new ArrayList<>();
        this.query("read setting " + name, "SELECT value FROM settings WHERE name = ?", rows -> values.add(rows.getString(1)), name);
        return values.isEmpty() ? null : values.getFirst();
    }

    public synchronized void setting(final String name, final @Nullable String value) {
        this.update("save setting " + name, "DELETE FROM settings WHERE name = ?", name);
        if (value != null) {
            this.update("save setting " + name, "INSERT INTO settings (name, value) VALUES (?, ?)", name, value);
        }
    }

    // ---- Helpers --------------------------------------------------------------------------

    @FunctionalInterface
    private interface RowReader {
        void read(ResultSet rows) throws SQLException;
    }

    private void update(final String what, final String sql, final @Nullable Object... parameters) {
        try (PreparedStatement statement = this.connection.prepareStatement(sql)) {
            bind(statement, parameters);
            statement.executeUpdate();
        } catch (final SQLException e) {
            this.logger.log(Level.SEVERE, "Database error while trying to " + what, e);
        }
    }

    private void query(final String what, final String sql, final RowReader reader, final @Nullable Object... parameters) {
        try (PreparedStatement statement = this.connection.prepareStatement(sql)) {
            bind(statement, parameters);
            try (ResultSet rows = statement.executeQuery()) {
                while (rows.next()) {
                    reader.read(rows);
                }
            }
        } catch (final SQLException e) {
            this.logger.log(Level.SEVERE, "Database error while trying to " + what, e);
        }
    }

    private static void bind(final PreparedStatement statement, final @Nullable Object[] parameters) throws SQLException {
        for (int i = 0; i < parameters.length; i++) {
            statement.setObject(i + 1, parameters[i]);
        }
    }
}
