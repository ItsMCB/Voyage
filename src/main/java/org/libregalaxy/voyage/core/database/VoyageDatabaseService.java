package org.libregalaxy.voyage.core.database;

import com.zaxxer.hikari.HikariConfig;
import com.zaxxer.hikari.HikariDataSource;
import org.bukkit.Bukkit;
import org.bukkit.configuration.file.FileConfiguration;
import org.bukkit.scheduler.BukkitTask;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;
import org.libregalaxy.voyage.VoyageContext;

import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.SQLException;
import java.sql.Statement;
import java.text.SimpleDateFormat;
import java.util.Arrays;
import java.util.Comparator;
import java.util.Date;
import java.util.concurrent.CompletableFuture;
import java.util.logging.Level;

/**
 * Manages the embedded SQLite connection pool, schema initialization,
 * automated rolling backups (VACUUM INTO), and safe administrative resets.
 */
public class VoyageDatabaseService {

    private final VoyageContext context;
    private final File dbFile;
    private HikariDataSource dataSource;
    private BukkitTask backupTask;

    public VoyageDatabaseService(@NotNull VoyageContext context) {
        this.context = context;
        File dataFolder = (context.getPlugin() != null && context.getPlugin().getDataFolder() != null)
                ? context.getPlugin().getDataFolder()
                : new File("build/tmp");
        this.dbFile = new File(dataFolder, "voyage.db");

        if (!dataFolder.exists()) {
            dataFolder.mkdirs();
        }

        initializeDataSource();
        applySchema();
        startAutoBackupTask();
    }

    public static @Nullable VoyageDatabaseService resolve(@Nullable VoyageContext context) {
        if (context == null) return null;
        try {
            VoyageDatabaseService service = context.getDatabaseService();
            if (service != null) return service;
        } catch (Throwable ignored) {}

        try {
            if (context.getPlugin() != null && context.getPlugin().getDataFolder() != null) {
                return new VoyageDatabaseService(context);
            }
        } catch (Throwable ignored) {}

        return null;
    }

    private synchronized void initializeDataSource() {
        HikariConfig config = new HikariConfig();
        config.setPoolName("Voyage-SQLite-Pool");
        config.setDriverClassName("org.sqlite.JDBC");
        config.setJdbcUrl("jdbc:sqlite:" + dbFile.getAbsolutePath());

        config.setMaximumPoolSize(4);
        config.setMinimumIdle(1);
        config.setConnectionTimeout(10000);
        config.setIdleTimeout(60000);

        config.setConnectionInitSql(
                "PRAGMA journal_mode = WAL;\n" +
                        "PRAGMA synchronous = NORMAL;\n" +
                        "PRAGMA busy_timeout = 8000;\n" +
                        "PRAGMA foreign_keys = ON;"
        );

        this.dataSource = new HikariDataSource(config);
    }

    private void applySchema() {
        try (Connection conn = getConnection();
             Statement stmt = conn.createStatement()) {

            stmt.execute("""
                CREATE TABLE IF NOT EXISTS voyage_worlds (
                    namespace           TEXT NOT NULL,
                    key                 TEXT NOT NULL,
                    environment         TEXT NOT NULL,
                    seed                INTEGER NOT NULL,
                    world_type          TEXT NOT NULL,
                    generator           TEXT,
                    generator_settings  TEXT,
                    auto_load           INTEGER NOT NULL DEFAULT 0,
                    difficulty          TEXT NOT NULL DEFAULT 'NORMAL',
                    generate_structures INTEGER NOT NULL DEFAULT 1,
                    spawn_x             INTEGER,
                    spawn_y             INTEGER,
                    spawn_z             INTEGER,
                    spawn_yaw           REAL,
                    spawn_pitch         REAL,
                    created_at          INTEGER NOT NULL,
                    PRIMARY KEY (namespace, key)
                );
            """);

            // Migration safeguards for dev databases
            try { stmt.execute("ALTER TABLE voyage_worlds ADD COLUMN spawn_yaw REAL;"); } catch (SQLException ignored) {}
            try { stmt.execute("ALTER TABLE voyage_worlds ADD COLUMN spawn_pitch REAL;"); } catch (SQLException ignored) {}

            stmt.execute("""
                CREATE TABLE IF NOT EXISTS voyage_world_gamerules (
                    world_namespace TEXT NOT NULL,
                    world_key       TEXT NOT NULL,
                    rule_name       TEXT NOT NULL,
                    rule_value      TEXT NOT NULL,
                    PRIMARY KEY (world_namespace, world_key, rule_name),
                    FOREIGN KEY (world_namespace, world_key) REFERENCES voyage_worlds(namespace, key) ON DELETE CASCADE
                );
            """);

            stmt.execute("""
                CREATE TABLE IF NOT EXISTS voyage_world_metadata (
                    world_namespace TEXT NOT NULL,
                    world_key       TEXT NOT NULL,
                    meta_key        TEXT NOT NULL,
                    meta_value      TEXT NOT NULL,
                    PRIMARY KEY (world_namespace, world_key, meta_key),
                    FOREIGN KEY (world_namespace, world_key) REFERENCES voyage_worlds(namespace, key) ON DELETE CASCADE
                );
            """);

            stmt.execute("""
                CREATE TABLE IF NOT EXISTS voyage_portal_links (
                    source_namespace TEXT NOT NULL,
                    source_key       TEXT NOT NULL,
                    portal_type      TEXT NOT NULL,
                    target_namespace TEXT NOT NULL,
                    target_key       TEXT NOT NULL,
                    strategy_type    TEXT NOT NULL,
                    strategy_factor  REAL,
                    generator_type   TEXT NOT NULL,
                    structure_key    TEXT,
                    PRIMARY KEY (source_namespace, source_key, portal_type),
                    FOREIGN KEY (source_namespace, source_key) REFERENCES voyage_worlds(namespace, key) ON DELETE CASCADE
                );
            """);

            stmt.execute("""
                CREATE TABLE IF NOT EXISTS voyage_player_profiles (
                    profile_id   TEXT NOT NULL,
                    player_uuid  TEXT NOT NULL,
                    state_json   TEXT NOT NULL,
                    last_updated INTEGER NOT NULL,
                    PRIMARY KEY (profile_id, player_uuid)
                );
            """);

            stmt.execute("CREATE INDEX IF NOT EXISTS idx_player_profiles_uuid ON voyage_player_profiles(player_uuid);");

        } catch (SQLException e) {
            log(Level.SEVERE, "Failed to apply Voyage database schema", e);
        }
    }

    public Connection getConnection() throws SQLException {
        if (dataSource == null || dataSource.isClosed()) {
            throw new SQLException("Voyage database connection pool is not active.");
        }
        return dataSource.getConnection();
    }

    private void startAutoBackupTask() {
        if (context.getPlugin() == null) return;
        boolean enabled = true;
        long intervalMinutes = 60L;

        try {
            FileConfiguration config = context.getPlugin().getConfig();
            if (config != null) {
                enabled = config.getBoolean("database.backup.enabled", true);
                intervalMinutes = config.getLong("database.backup.interval-minutes", 60L);
            }
        } catch (Throwable ignored) {}

        if (!enabled) return;

        long ticks = Math.max(20L * 60L, intervalMinutes * 60L * 20L);

        try {
            if (Bukkit.getScheduler() != null) {
                this.backupTask = Bukkit.getScheduler().runTaskTimerAsynchronously(context.getPlugin(), () -> {
                    try {
                        performRollingBackup(null);
                    } catch (Exception e) {
                        log(Level.WARNING, "Automated database backup failed", e);
                    }
                }, ticks, ticks);
            }
        } catch (Throwable ignored) {}
    }

    public synchronized Path performRollingBackup(@Nullable String customLabel) throws IOException, SQLException {
        String folderName = "backups/voyage";
        try {
            if (context.getPlugin() != null && context.getPlugin().getConfig() != null) {
                folderName = context.getPlugin().getConfig().getString("database.backup.folder", folderName);
            }
        } catch (Throwable ignored) {}

        Path backupDir = (context.getPlugin() != null && context.getPlugin().getDataFolder() != null)
                ? context.getPlugin().getDataFolder().toPath().resolve(folderName)
                : Path.of("build/tmp/" + folderName);
        Files.createDirectories(backupDir);

        String timestamp = new SimpleDateFormat("yyyy-MM-dd_HH-mm-ss").format(new Date());
        String fileName = "voyage_" + (customLabel != null && !customLabel.isBlank() ? customLabel + "_" : "") + timestamp + ".db";
        Path targetFile = backupDir.resolve(fileName);

        String escapedPath = targetFile.toAbsolutePath().toString().replace("'", "''");
        try (Connection conn = getConnection();
             Statement stmt = conn.createStatement()) {
            stmt.execute("VACUUM INTO '" + escapedPath + "';");
        }

        log(Level.INFO, "Successfully created database snapshot: " + targetFile.getFileName(), null);

        int maxRetained = 3;
        try {
            if (context.getPlugin() != null && context.getPlugin().getConfig() != null) {
                maxRetained = context.getPlugin().getConfig().getInt("database.backup.max-retained-dumps", 3);
            }
        } catch (Throwable ignored) {}
        pruneOldBackups(backupDir, maxRetained);

        return targetFile;
    }

    private void pruneOldBackups(Path backupDir, int maxRetained) {
        if (maxRetained <= 0) return;

        File[] files = backupDir.toFile().listFiles((dir, name) -> name.startsWith("voyage_") && name.endsWith(".db"));
        if (files == null || files.length <= maxRetained) return;

        Arrays.sort(files, Comparator.comparingLong(File::lastModified));

        int toDelete = files.length - maxRetained;
        for (int i = 0; i < toDelete; i++) {
            File stale = files[i];
            if (stale.delete()) {
                log(Level.INFO, "Pruned old database backup: " + stale.getName(), null);
            }
        }
    }

    public synchronized CompletableFuture<Void> resetDatabase() {
        return CompletableFuture.runAsync(() -> {
            if (backupTask != null) {
                backupTask.cancel();
            }

            if (dataSource != null && !dataSource.isClosed()) {
                dataSource.close();
            }

            File walFile = new File(dbFile.getParentFile(), dbFile.getName() + "-wal");
            File shmFile = new File(dbFile.getParentFile(), dbFile.getName() + "-shm");

            dbFile.delete();
            walFile.delete();
            shmFile.delete();

            log(Level.WARNING, "Voyage database has been completely wiped.", null);

            initializeDataSource();
            applySchema();
            startAutoBackupTask();
        });
    }

    public synchronized void shutdown() {
        if (backupTask != null) {
            backupTask.cancel();
            backupTask = null;
        }

        if (dataSource != null && !dataSource.isClosed()) {
            try {
                try (Connection conn = dataSource.getConnection();
                     Statement stmt = conn.createStatement()) {
                    stmt.execute("PRAGMA wal_checkpoint(TRUNCATE);");
                } catch (Exception ignored) {}

                dataSource.close();
            } catch (Exception e) {
                log(Level.SEVERE, "Error shutting down Voyage database", e);
            }
        }
    }

    private void log(Level level, String msg, @Nullable Throwable t) {
        try {
            if (context.getPlugin() != null && context.getPlugin().getLogger() != null) {
                if (t != null) {
                    context.getPlugin().getLogger().log(level, msg, t);
                } else {
                    context.getPlugin().getLogger().log(level, msg);
                }
            }
        } catch (Throwable ignored) {}
    }
}