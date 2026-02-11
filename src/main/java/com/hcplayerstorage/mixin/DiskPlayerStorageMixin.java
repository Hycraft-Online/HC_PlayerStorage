package com.hcplayerstorage.mixin;

import com.hypixel.hytale.component.Holder;
import com.hypixel.hytale.server.core.universe.playerdata.DiskPlayerStorageProvider;
import com.hypixel.hytale.server.core.universe.world.storage.EntityStore;
import com.hypixel.hytale.server.core.util.BsonUtil;
import org.bson.BsonDocument;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Overwrite;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

import javax.annotation.Nonnull;
import java.io.File;
import java.io.IOException;
import java.net.URL;
import java.net.URLClassLoader;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.Driver;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.HashSet;
import java.util.Properties;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.logging.Level;
import java.util.logging.Logger;

@Mixin(DiskPlayerStorageProvider.DiskPlayerStorage.class)
public class DiskPlayerStorageMixin {

    private static final Logger LOGGER = Logger.getLogger("HC_PlayerStorage");
    private static final String JDBC_URL = "jdbc:postgresql://postgres:5432/factionwars";
    private static final String JDBC_USER = "factionwars";
    private static final String JDBC_PASS = "factionwars_secret";
    private static volatile boolean initialized = false;
    private static volatile Driver pgDriver;

    private static final String DRIVER_JAR = "/home/hytale/server-files/mods/HC_PlayerInventory-1.0.0.jar";

    /**
     * Load the PostgreSQL JDBC driver from HC_PlayerInventory.
     * Mixin code runs in the TransformingClassLoader which doesn't have the PG driver
     * on its classpath. We use a URLClassLoader to load it from the companion plugin JAR,
     * then call Driver.connect() directly (bypassing DriverManager's classloader isolation).
     */
    private static synchronized void loadDriver() {
        if (pgDriver != null) return;

        File jar = new File(DRIVER_JAR);
        if (!jar.exists()) {
            LOGGER.severe("HC_PlayerInventory JAR not found at " + DRIVER_JAR +
                " - cannot load PostgreSQL driver");
            return;
        }

        try {
            URLClassLoader cl = new URLClassLoader(
                new URL[]{jar.toURI().toURL()},
                ClassLoader.getSystemClassLoader()
            );
            Class<?> driverClass = Class.forName("org.postgresql.Driver", true, cl);
            pgDriver = (Driver) driverClass.getDeclaredConstructor().newInstance();
            LOGGER.info("Loaded PostgreSQL driver from " + jar.getName());
        } catch (Exception e) {
            LOGGER.log(Level.SEVERE, "Failed to load PostgreSQL driver from " + jar.getName(), e);
        }
    }

    private static Connection getConnection() throws SQLException {
        loadDriver();
        if (pgDriver == null) {
            throw new SQLException("PostgreSQL JDBC driver not available");
        }
        Properties props = new Properties();
        props.setProperty("user", JDBC_USER);
        props.setProperty("password", JDBC_PASS);
        Connection conn = pgDriver.connect(JDBC_URL, props);
        if (conn == null) {
            throw new SQLException("Driver returned null connection for " + JDBC_URL);
        }
        return conn;
    }

    private static synchronized void ensureInitialized() {
        if (initialized) return;
        try (Connection conn = getConnection()) {
            try (PreparedStatement stmt = conn.prepareStatement(
                    "CREATE TABLE IF NOT EXISTS player_data (" +
                    "uuid UUID PRIMARY KEY, " +
                    "data JSONB NOT NULL, " +
                    "updated_at TIMESTAMP WITH TIME ZONE DEFAULT NOW()" +
                    ")")) {
                stmt.execute();
            }
            try (PreparedStatement stmt = conn.prepareStatement(
                    "CREATE INDEX IF NOT EXISTS idx_player_data_updated ON player_data(updated_at)")) {
                stmt.execute();
            }
            initialized = true;
            LOGGER.info("PlayerDatabase initialized (player_data table ready)");
        } catch (SQLException e) {
            LOGGER.log(Level.SEVERE, "Failed to initialize player_data table", e);
        }
    }

    @Inject(method = "<init>", at = @At("RETURN"))
    private void onInit(Path path, CallbackInfo ci) {
        ensureInitialized();
        LOGGER.info("DiskPlayerStorage replaced with PostgreSQL-backed storage");
    }

    /**
     * @author HC_PlayerStorage
     * @reason Replace disk-based player loading with PostgreSQL
     */
    @Overwrite
    @Nonnull
    public CompletableFuture<Holder<EntityStore>> load(@Nonnull UUID uuid) {
        return CompletableFuture.supplyAsync(() -> {
            try {
                String json = null;
                try (Connection conn = getConnection();
                     PreparedStatement stmt = conn.prepareStatement(
                         "SELECT data::text FROM player_data WHERE uuid = ?")) {
                    stmt.setObject(1, uuid);
                    ResultSet rs = stmt.executeQuery();
                    if (rs.next()) {
                        json = rs.getString(1);
                    }
                }
                BsonDocument doc = (json != null) ? BsonDocument.parse(json) : new BsonDocument();
                return EntityStore.REGISTRY.deserialize(doc);
            } catch (Exception e) {
                LOGGER.log(Level.SEVERE, "Failed to load player " + uuid + " from DB, returning empty", e);
                return EntityStore.REGISTRY.deserialize(new BsonDocument());
            }
        });
    }

    /**
     * @author HC_PlayerStorage
     * @reason Replace disk-based player saving with PostgreSQL
     */
    @Overwrite
    @Nonnull
    public CompletableFuture<Void> save(@Nonnull UUID uuid, @Nonnull Holder<EntityStore> holder) {
        return CompletableFuture.runAsync(() -> {
            try {
                BsonDocument doc = EntityStore.REGISTRY.serialize(holder);
                String json = BsonUtil.toJson(doc);
                try (Connection conn = getConnection();
                     PreparedStatement stmt = conn.prepareStatement(
                         "INSERT INTO player_data (uuid, data, updated_at) VALUES (?, ?::jsonb, NOW()) " +
                         "ON CONFLICT (uuid) DO UPDATE SET data = EXCLUDED.data, updated_at = NOW()")) {
                    stmt.setObject(1, uuid);
                    stmt.setString(2, json);
                    stmt.executeUpdate();
                }
            } catch (Exception e) {
                LOGGER.log(Level.SEVERE, "Failed to save player " + uuid + " to DB", e);
            }
        });
    }

    /**
     * @author HC_PlayerStorage
     * @reason Replace disk-based player removal with PostgreSQL
     */
    @Overwrite
    @Nonnull
    public CompletableFuture<Void> remove(@Nonnull UUID uuid) {
        return CompletableFuture.runAsync(() -> {
            try (Connection conn = getConnection();
                 PreparedStatement stmt = conn.prepareStatement(
                     "DELETE FROM player_data WHERE uuid = ?")) {
                stmt.setObject(1, uuid);
                stmt.executeUpdate();
            } catch (Exception e) {
                LOGGER.log(Level.SEVERE, "Failed to remove player " + uuid + " from DB", e);
            }
        });
    }

    /**
     * @author HC_PlayerStorage
     * @reason Replace disk-based player listing with PostgreSQL
     */
    @Overwrite
    @Nonnull
    public Set<UUID> getPlayers() throws IOException {
        Set<UUID> uuids = new HashSet<>();
        try (Connection conn = getConnection();
             PreparedStatement stmt = conn.prepareStatement("SELECT uuid FROM player_data")) {
            ResultSet rs = stmt.executeQuery();
            while (rs.next()) {
                uuids.add(rs.getObject(1, UUID.class));
            }
        } catch (SQLException e) {
            LOGGER.log(Level.SEVERE, "Failed to get players from DB", e);
            throw new IOException("Failed to query player list from database", e);
        }
        return uuids;
    }
}
