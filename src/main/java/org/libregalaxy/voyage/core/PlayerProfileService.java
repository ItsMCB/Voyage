package org.libregalaxy.voyage.core;

import com.google.gson.Gson;
import org.bukkit.Bukkit;
import org.bukkit.NamespacedKey;
import org.bukkit.World;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.entity.Player;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;
import org.libregalaxy.voyage.VoyageContext;
import org.libregalaxy.voyage.api.VoyageAPI;
import org.libregalaxy.voyage.api.events.VoyageWorldPreLinkEvent;
import org.libregalaxy.voyage.core.database.VoyageDatabaseService;

import java.io.File;
import java.io.IOException;
import java.nio.file.FileVisitResult;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.SimpleFileVisitor;
import java.nio.file.attribute.BasicFileAttributes;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.locks.ReentrantReadWriteLock;
import java.util.logging.Level;

/**
 * Manages player profile state persistence (inventories, ender chests, health, XP,
 * and locations) across isolated worlds and shared world groups using SQLite.
 */
public class PlayerProfileService {

    private final VoyageContext context;
    private final Gson gson;
    private final VoyageDatabaseService databaseService;

    private record ProfileKey(String profileId, UUID uuid) {}

    private final Map<ProfileKey, PlayerStateSerializer.PlayerState> pendingWrites = new ConcurrentHashMap<>();
    private final Set<ProfileKey> scheduledWrites = ConcurrentHashMap.newKeySet();

    private final ExecutorService profileWriter = Executors.newSingleThreadExecutor(r -> new Thread(r, "Voyage-Profile-Writer"));

    private final Map<NamespacedKey, String> profileIdCache = new ConcurrentHashMap<>();
    private final ReentrantReadWriteLock profileMigrationLock = new ReentrantReadWriteLock();

    public static final int CURRENT_SCHEMA = 1;

    public PlayerProfileService(@NotNull VoyageContext context) {
        this.context = context;
        this.gson = new Gson();
        this.databaseService = VoyageDatabaseService.resolve(context);
    }

    public void shutdown() {
        profileWriter.shutdown();
        try {
            if (!profileWriter.awaitTermination(5, TimeUnit.SECONDS)) {
                forceFlushPendingWrites();
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }

    public void forceFlushPendingWrites() {
        for (Map.Entry<ProfileKey, PlayerStateSerializer.PlayerState> entry : pendingWrites.entrySet()) {
            ProfileKey key = entry.getKey();
            PlayerStateSerializer.PlayerState toWrite = pendingWrites.get(key);
            if (toWrite != null) {
                try {
                    savePlayerStateToDatabaseSync(key.profileId(), key.uuid(), toWrite);
                } catch (Exception e) {
                    context.getPlugin().getLogger().severe("Failed to force-flush data for " + key.uuid() + ": " + e.getMessage());
                } finally {
                    pendingWrites.remove(key, toWrite);
                }
            }
        }
        scheduledWrites.clear();
    }

    public void loadAll() {}

    public void reload() {
        invalidateProfileCache();
        saveAllOnlineAsync();
    }

    public void invalidateProfileCache() {
        profileIdCache.clear();
    }

    public String getProfileId(@NotNull NamespacedKey worldKey) {
        String cached = profileIdCache.get(worldKey);
        if (cached != null) return cached;

        profileMigrationLock.readLock().lock();
        try {
            return profileIdCache.computeIfAbsent(worldKey, k -> {
                ConfigurationSection groups = context.getPlugin().getConfig().getConfigurationSection("world-groups");
                if (groups != null) {
                    for (String group : groups.getKeys(false)) {
                        List<String> worlds = groups.getStringList(group);
                        for (String entry : worlds) {
                            if (matchesWorldKey(entry, k)) {
                                return "group:" + group;
                            }
                        }
                    }
                }
                return getDefaultProfileId(k);
            });
        } finally {
            profileMigrationLock.readLock().unlock();
        }
    }

    public String getDefaultProfileId(@NotNull NamespacedKey worldKey) {
        return "isolated:" + worldKey.getNamespace() + "_" + worldKey.getKey();
    }

    private boolean matchesWorldKey(String entry, NamespacedKey key) {
        if (entry == null || entry.isBlank()) return false;
        if (entry.equalsIgnoreCase(key.asString())) return true;
        NamespacedKey parsed = NamespacedKey.fromString(entry);
        if (key.equals(parsed)) return true;
        return key.getNamespace().equals(NamespacedKey.MINECRAFT) && entry.equalsIgnoreCase(key.getKey());
    }

    private synchronized void savePlayerStateToDatabaseSync(String profileId, UUID uuid, PlayerStateSerializer.PlayerState state) throws SQLException {
        // Preserve offline locations from previous sessions
        if (state.worldLocations == null) {
            state.worldLocations = new HashMap<>();
        }
        if (state.lastLocWorld != null) {
            state.worldLocations.put(state.lastLocWorld, new PlayerStateSerializer.LocationData(
                    state.lastLocX, state.lastLocY, state.lastLocZ, state.lastLocYaw, state.lastLocPitch));
        }

        // Fast index check to merge any previously stored world locations
        try (Connection conn = databaseService.getConnection();
             PreparedStatement ps = conn.prepareStatement(
                     "SELECT state_json FROM voyage_player_profiles WHERE profile_id = ? AND player_uuid = ?;")) {
            ps.setString(1, profileId);
            ps.setString(2, uuid.toString());
            try (ResultSet rs = ps.executeQuery()) {
                if (rs.next()) {
                    String existingJson = rs.getString("state_json");
                    PlayerStateSerializer.PlayerState existing = gson.fromJson(existingJson, PlayerStateSerializer.PlayerState.class);
                    if (existing != null && existing.worldLocations != null) {
                        for (Map.Entry<String, PlayerStateSerializer.LocationData> entry : existing.worldLocations.entrySet()) {
                            state.worldLocations.putIfAbsent(entry.getKey(), entry.getValue());
                        }
                    }
                }
            }
        }

        String json = gson.toJson(state);
        long now = System.currentTimeMillis();

        String upsertSql = """
            INSERT INTO voyage_player_profiles (profile_id, player_uuid, state_json, last_updated)
            VALUES (?, ?, ?, ?)
            ON CONFLICT(profile_id, player_uuid) DO UPDATE SET
                state_json = excluded.state_json,
                last_updated = excluded.last_updated;
        """;

        try (Connection conn = databaseService.getConnection();
             PreparedStatement ps = conn.prepareStatement(upsertSql)) {
            ps.setString(1, profileId);
            ps.setString(2, uuid.toString());
            ps.setString(3, json);
            ps.setLong(4, now);
            ps.executeUpdate();
        }
    }

    public void savePlayerStateAsync(String profileId, UUID uuid, PlayerStateSerializer.PlayerState state) {
        ProfileKey key = new ProfileKey(profileId, uuid);
        pendingWrites.put(key, state);

        if (scheduledWrites.add(key)) {
            profileWriter.submit(() -> {
                scheduledWrites.remove(key);
                PlayerStateSerializer.PlayerState toWrite = pendingWrites.get(key);
                if (toWrite != null) {
                    try {
                        savePlayerStateToDatabaseSync(profileId, uuid, toWrite);
                    } catch (SQLException e) {
                        context.getPlugin().getLogger().log(Level.SEVERE, "Failed async player save for " + uuid, e);
                    } finally {
                        pendingWrites.remove(key, toWrite);
                    }
                }
            });
        }
    }

    public void savePlayerStateSync(String profileId, UUID uuid, PlayerStateSerializer.PlayerState state) {
        ProfileKey key = new ProfileKey(profileId, uuid);
        pendingWrites.put(key, state);
        if (!profileWriter.isShutdown()) {
            try {
                profileWriter.submit(() -> {
                    scheduledWrites.remove(key);
                    PlayerStateSerializer.PlayerState toWrite = pendingWrites.get(key);
                    if (toWrite != null) {
                        try {
                            savePlayerStateToDatabaseSync(profileId, uuid, toWrite);
                        } catch (SQLException e) {
                            context.getPlugin().getLogger().log(Level.SEVERE, "Failed sync player save task for " + uuid, e);
                        } finally {
                            pendingWrites.remove(key, toWrite);
                        }
                    }
                }).get(5, TimeUnit.SECONDS);
                return;
            } catch (Exception ignored) {}
        }
        scheduledWrites.remove(key);
        try {
            savePlayerStateToDatabaseSync(profileId, uuid, state);
        } catch (SQLException e) {
            context.getPlugin().getLogger().log(Level.SEVERE, "Failed sync player save for " + uuid, e);
        } finally {
            pendingWrites.remove(key, state);
        }
    }

    /**
     * Reads a player state from in-memory pending queue or SQLite.
     */
    public PlayerStateSerializer.PlayerState loadPlayerStateFromFile(String profileId, UUID uuid) {
        ProfileKey key = new ProfileKey(profileId, uuid);

        PlayerStateSerializer.PlayerState pending = pendingWrites.get(key);
        if (pending != null) return pending;

        try (Connection conn = databaseService.getConnection();
             PreparedStatement ps = conn.prepareStatement(
                     "SELECT state_json FROM voyage_player_profiles WHERE profile_id = ? AND player_uuid = ?;")) {
            ps.setString(1, profileId);
            ps.setString(2, uuid.toString());
            try (ResultSet rs = ps.executeQuery()) {
                if (rs.next()) {
                    String json = rs.getString("state_json");
                    return gson.fromJson(json, PlayerStateSerializer.PlayerState.class);
                }
            }
        } catch (SQLException e) {
            context.getPlugin().getLogger().log(Level.SEVERE, "Database error loading player " + uuid, e);
        }

        return null;
    }

    public void saveAllOnlineAsync() {
        List<PlayerStateSerializer.PlayerStateCapture> captures = new ArrayList<>();
        PlayerDataManager pdm = context.getService(PlayerDataManager.class);
        List<Player> snapshot = List.copyOf(Bukkit.getOnlinePlayers());
        for (Player p : snapshot) {
            if (pdm != null && pdm.isSwitchingProfiles(p.getUniqueId())) continue;
            captures.add(new PlayerStateSerializer.PlayerStateCapture(
                    getProfileId(p.getWorld().getKey()),
                    p.getUniqueId(),
                    pdm.getStateSerializer().capturePlayerState(p, p.getWorld(), null)));
        }
        for (PlayerStateSerializer.PlayerStateCapture capture : captures) {
            savePlayerStateAsync(capture.profileId(), capture.uuid(), capture.state());
        }
    }

    public void removeWorldFromGroups(@NotNull NamespacedKey worldKey) {
        profileMigrationLock.writeLock().lock();
        try {
            invalidateProfileCache();

            ConfigurationSection groups = context.getPlugin().getConfig().getConfigurationSection("world-groups");
            if (groups != null) {
                boolean changed = false;
                for (String group : groups.getKeys(false)) {
                    List<String> worlds = groups.getStringList(group);
                    boolean removed = worlds.removeIf(w -> matchesWorldKey(w, worldKey));
                    if (removed) {
                        groups.set(group, worlds.isEmpty() ? null : worlds);
                        if (worlds.isEmpty()) {
                            deleteProfileRecordsAsync("group:" + group);
                        } else {
                            cleanWorldLocationsFromProfiles(worldKey, "group:" + group);
                        }
                        changed = true;
                    }
                }
                if (changed) {
                    context.getPlugin().saveConfig();
                }
            }
        } finally {
            profileMigrationLock.writeLock().unlock();
        }
    }

    public void deleteWorldData(@NotNull NamespacedKey worldKey) {
        removeWorldFromGroups(worldKey);

        String defaultProfileId = getDefaultProfileId(worldKey);
        deleteProfileRecordsAsync(defaultProfileId);
    }

    private void deleteProfileRecordsAsync(String profileId) {
        pendingWrites.keySet().removeIf(k -> k.profileId().equals(profileId));
        scheduledWrites.removeIf(k -> k.profileId().equals(profileId));

        Runnable task = () -> {
            try (Connection conn = databaseService.getConnection();
                 PreparedStatement ps = conn.prepareStatement("DELETE FROM voyage_player_profiles WHERE profile_id = ?;")) {
                ps.setString(1, profileId);
                ps.executeUpdate();
            } catch (SQLException e) {
                context.getPlugin().getLogger().log(Level.SEVERE, "Failed to delete profiles for " + profileId, e);
            }
            deleteProfileFolderOnDisk(profileId);
        };

        if (!profileWriter.isShutdown()) {
            profileWriter.submit(task);
        } else {
            task.run();
        }
    }

    public void cleanWorldLocationsFromProfiles(@NotNull NamespacedKey worldKey, @NotNull String profileId) {
        String worldKeyStr = worldKey.asString();
        for (Map.Entry<ProfileKey, PlayerStateSerializer.PlayerState> entry : pendingWrites.entrySet()) {
            if (entry.getKey().profileId().equals(profileId)) {
                PlayerStateSerializer.PlayerState state = entry.getValue();
                if (state != null) {
                    cleanStateForDeletedWorld(state, worldKeyStr);
                }
            }
        }

        Runnable task = () -> {
            try (Connection conn = databaseService.getConnection()) {
                String select = "SELECT player_uuid, state_json FROM voyage_player_profiles WHERE profile_id = ?;";
                Map<String, String> updatedStates = new HashMap<>();
                try (PreparedStatement ps = conn.prepareStatement(select)) {
                    ps.setString(1, profileId);
                    try (ResultSet rs = ps.executeQuery()) {
                        while (rs.next()) {
                            String uuidStr = rs.getString("player_uuid");
                            String json = rs.getString("state_json");
                            PlayerStateSerializer.PlayerState state = gson.fromJson(json, PlayerStateSerializer.PlayerState.class);
                            if (state != null && cleanStateForDeletedWorld(state, worldKeyStr)) {
                                updatedStates.put(uuidStr, gson.toJson(state));
                            }
                        }
                    }
                }
                if (!updatedStates.isEmpty()) {
                    String update = "UPDATE voyage_player_profiles SET state_json = ?, last_updated = ? WHERE profile_id = ? AND player_uuid = ?;";
                    try (PreparedStatement ps = conn.prepareStatement(update)) {
                        long now = System.currentTimeMillis();
                        for (Map.Entry<String, String> entry : updatedStates.entrySet()) {
                            ps.setString(1, entry.getValue());
                            ps.setLong(2, now);
                            ps.setString(3, profileId);
                            ps.setString(4, entry.getKey());
                            ps.addBatch();
                        }
                        ps.executeBatch();
                    }
                }
            } catch (SQLException e) {
                context.getPlugin().getLogger().log(Level.WARNING, "Failed to clean deleted world locations for " + worldKeyStr, e);
            }
        };

        if (!profileWriter.isShutdown()) {
            profileWriter.submit(task);
        } else {
            task.run();
        }
    }

    private boolean cleanStateForDeletedWorld(PlayerStateSerializer.PlayerState state, String worldKeyStr) {
        boolean changed = false;
        if (state.worldLocations != null && state.worldLocations.remove(worldKeyStr) != null) {
            changed = true;
        }
        if (worldKeyStr.equals(state.lastLocWorld)) {
            state.lastLocWorld = null;
            changed = true;
        }
        return changed;
    }

    private void deleteProfileFolderOnDisk(String profileId) {
        File dataFolder = (context.getPlugin() != null && context.getPlugin().getDataFolder() != null)
                ? context.getPlugin().getDataFolder()
                : null;
        if (dataFolder == null || !dataFolder.exists()) return;

        File profilesDir = new File(new File(dataFolder, "playerdata"), "profiles");
        if (!profilesDir.exists() || !profilesDir.isDirectory()) return;

        String folderName = profileId.replace(":", "_");
        File targetFolder = new File(profilesDir, folderName);
        deleteDirectoryRecursively(targetFolder);

        if (folderName.startsWith("isolated_")) {
            File alt = new File(profilesDir, folderName.substring("isolated_".length()));
            deleteDirectoryRecursively(alt);
        } else if (folderName.startsWith("group_")) {
            File alt = new File(profilesDir, folderName.substring("group_".length()));
            deleteDirectoryRecursively(alt);
        }

        try {
            File[] files = profilesDir.listFiles();
            if (files != null && files.length == 0) {
                profilesDir.delete();
                File parent = profilesDir.getParentFile();
                if (parent != null) {
                    File[] parentFiles = parent.listFiles();
                    if (parentFiles != null && parentFiles.length == 0) {
                        parent.delete();
                    }
                }
            }
        } catch (Exception ignored) {}
    }

    private void deleteDirectoryRecursively(@Nullable File folder) {
        if (folder == null || !folder.exists()) return;
        try {
            Files.walkFileTree(folder.toPath(), new SimpleFileVisitor<>() {
                @Override
                public FileVisitResult visitFile(Path file, BasicFileAttributes attrs) throws IOException {
                    Files.deleteIfExists(file);
                    return FileVisitResult.CONTINUE;
                }

                @Override
                public FileVisitResult postVisitDirectory(Path dir, IOException exc) throws IOException {
                    Files.deleteIfExists(dir);
                    return FileVisitResult.CONTINUE;
                }
            });
        } catch (IOException e) {
            context.getPlugin().getLogger().warning("Failed to delete profile directory: " + folder.getAbsolutePath() + ": " + e.getMessage());
        }
    }

    public VoyageAPI.DataLinkPreview previewProfileMerge(NamespacedKey worldKey, String targetProfileIdRaw, VoyageAPI.DataLinkMergeStrategy strategy) {
        String sourceId = getProfileId(worldKey);
        String targetId;
        if (targetProfileIdRaw == null || targetProfileIdRaw.equalsIgnoreCase("none") || targetProfileIdRaw.equals(getDefaultProfileId(worldKey))) {
            targetId = getDefaultProfileId(worldKey);
        } else if (targetProfileIdRaw.startsWith("group:") || targetProfileIdRaw.startsWith("isolated:")) {
            targetId = targetProfileIdRaw;
        } else {
            targetId = "group:" + targetProfileIdRaw;
        }

        VoyageAPI.DataLinkMergeStrategy effectiveStrategy = (strategy != null) ? strategy : VoyageAPI.DataLinkMergeStrategy.DESTINATION_PRIORITY;

        if (sourceId.equals(targetId)) return new VoyageAPI.DataLinkPreview(0, 0, 0, effectiveStrategy);

        Set<String> sourceUuids = getPlayerUuidsInProfile(sourceId);
        Set<String> targetUuids = getPlayerUuidsInProfile(targetId);

        try {
            World sourceWorld = Bukkit.getWorld(worldKey);
            if (sourceWorld != null) {
                for (Player p : sourceWorld.getPlayers()) sourceUuids.add(p.getUniqueId().toString());
            }
        } catch (Exception ignored) {}

        for (ProfileKey pk : pendingWrites.keySet()) {
            if (pk.profileId().equals(sourceId)) sourceUuids.add(pk.uuid().toString());
            if (pk.profileId().equals(targetId)) targetUuids.add(pk.uuid().toString());
        }

        int conflicts = 0;
        int newToTarget = 0;
        for (String uuid : sourceUuids) {
            if (targetUuids.contains(uuid)) conflicts++;
            else newToTarget++;
        }
        int targetOnly = targetUuids.size() - conflicts;

        return new VoyageAPI.DataLinkPreview(newToTarget, conflicts, targetOnly, effectiveStrategy);
    }

    private Set<String> getPlayerUuidsInProfile(String profileId) {
        Set<String> uuids = new HashSet<>();
        try (Connection conn = databaseService.getConnection();
             PreparedStatement ps = conn.prepareStatement("SELECT player_uuid FROM voyage_player_profiles WHERE profile_id = ?;")) {
            ps.setString(1, profileId);
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) uuids.add(rs.getString("player_uuid"));
            }
        } catch (SQLException e) {
            context.getPlugin().getLogger().log(Level.SEVERE, "Failed to load player UUIDs for profile: " + profileId, e);
        }
        return uuids;
    }

    public CompletableFuture<Void> setProfileId(@NotNull NamespacedKey worldKey, String newProfileIdRaw, VoyageAPI.DataLinkMergeStrategy strategy, java.util.function.DoubleConsumer progress) {
        String sourceId = getProfileId(worldKey);

        String targetGroup;
        String targetId;
        if (newProfileIdRaw == null || newProfileIdRaw.equalsIgnoreCase("none") || newProfileIdRaw.equals(getDefaultProfileId(worldKey))) {
            targetGroup = null;
            targetId = getDefaultProfileId(worldKey);
        } else if (newProfileIdRaw.startsWith("group:")) {
            targetGroup = newProfileIdRaw.substring(6);
            targetId = newProfileIdRaw;
        } else if (newProfileIdRaw.startsWith("isolated:")) {
            targetGroup = null;
            targetId = newProfileIdRaw;
        } else {
            targetGroup = newProfileIdRaw;
            targetId = "group:" + newProfileIdRaw;
        }

        if (sourceId.equals(targetId)) return CompletableFuture.completedFuture(null);

        VoyageAPI.DataLinkMergeStrategy effectiveStrategy = (strategy != null) ? strategy : VoyageAPI.DataLinkMergeStrategy.DESTINATION_PRIORITY;

        CompletableFuture<Boolean> eventFuture = new CompletableFuture<>();
        Runnable eventTask = () -> {
            VoyageWorldPreLinkEvent event = new VoyageWorldPreLinkEvent(worldKey, targetId, effectiveStrategy);
            Bukkit.getPluginManager().callEvent(event);
            eventFuture.complete(!event.isCancelled());
        };

        if (Bukkit.isPrimaryThread()) eventTask.run();
        else Bukkit.getScheduler().runTask(context.getPlugin(), eventTask);

        return eventFuture.thenCompose(notCancelled -> {
            if (!notCancelled) return CompletableFuture.completedFuture(null);

            CompletableFuture<Void> preparePlayersFuture = new CompletableFuture<>();
            Runnable prepareTask = () -> {
                World bukkitWorld = Bukkit.getWorld(worldKey);
                if (bukkitWorld != null && !bukkitWorld.getPlayers().isEmpty()) {
                    for (Player p : bukkitWorld.getPlayers()) {
                        context.getService(PlayerDataManager.class).savePlayerStateSync(p);
                    }
                }
                preparePlayersFuture.complete(null);
            };

            if (Bukkit.isPrimaryThread()) prepareTask.run();
            else Bukkit.getScheduler().runTask(context.getPlugin(), prepareTask);

            return preparePlayersFuture.thenCompose(v -> CompletableFuture.runAsync(() -> {
                forceFlushPendingWrites();

                try (Connection conn = databaseService.getConnection()) {
                    conn.setAutoCommit(false);
                    try {
                        if (targetId.startsWith("isolated:")) {
                            try (PreparedStatement delTarget = conn.prepareStatement(
                                    "DELETE FROM voyage_player_profiles WHERE profile_id = ?;")) {
                                delTarget.setString(1, targetId);
                                delTarget.executeUpdate();
                            }
                        }

                        // SQL-level merge based on strategy
                        String mergeSql = (effectiveStrategy == VoyageAPI.DataLinkMergeStrategy.SOURCE_PRIORITY)
                                ? """
                                    INSERT INTO voyage_player_profiles (profile_id, player_uuid, state_json, last_updated)
                                    SELECT ?, player_uuid, state_json, ? FROM voyage_player_profiles WHERE profile_id = ?
                                    ON CONFLICT(profile_id, player_uuid) DO UPDATE SET
                                        state_json = excluded.state_json,
                                        last_updated = excluded.last_updated;
                                """
                                : """
                                    INSERT INTO voyage_player_profiles (profile_id, player_uuid, state_json, last_updated)
                                    SELECT ?, player_uuid, state_json, ? FROM voyage_player_profiles WHERE profile_id = ?
                                    ON CONFLICT(profile_id, player_uuid) DO NOTHING;
                                """;

                        try (PreparedStatement ps = conn.prepareStatement(mergeSql)) {
                            ps.setString(1, targetId);
                            ps.setLong(2, System.currentTimeMillis());
                            ps.setString(3, sourceId);
                            ps.executeUpdate();
                        }

                        if (sourceId.startsWith("isolated:")) {
                            try (PreparedStatement delSource = conn.prepareStatement(
                                    "DELETE FROM voyage_player_profiles WHERE profile_id = ?;")) {
                                delSource.setString(1, sourceId);
                                delSource.executeUpdate();
                            }
                            deleteProfileFolderOnDisk(sourceId);
                        }

                        conn.commit();
                        if (progress != null) progress.accept(1.0);
                    } catch (SQLException ex) {
                        conn.rollback();
                        throw new CompletionException("Database transaction failed during profile link", ex);
                    } finally {
                        conn.setAutoCommit(true);
                    }
                } catch (SQLException e) {
                    throw new CompletionException(e);
                }
            }, profileWriter)).thenCompose(v -> {
                CompletableFuture<Void> syncFuture = new CompletableFuture<>();
                Bukkit.getScheduler().runTask(context.getPlugin(), () -> {
                    profileMigrationLock.writeLock().lock();
                    try {
                        invalidateProfileCache();

                        ConfigurationSection groups = context.getPlugin().getConfig().getConfigurationSection("world-groups");
                        if (groups == null) groups = context.getPlugin().getConfig().createSection("world-groups");

                        for (String group : groups.getKeys(false)) {
                            List<String> worlds = groups.getStringList(group);
                            boolean removed = worlds.removeIf(w -> matchesWorldKey(w, worldKey));
                            if (removed) {
                                groups.set(group, worlds.isEmpty() ? null : worlds);
                                if (worlds.isEmpty()) {
                                    deleteProfileRecordsAsync("group:" + group);
                                }
                            }
                        }

                        if (targetGroup != null) {
                            List<String> worlds = groups.getStringList(targetGroup);
                            boolean alreadyPresent = false;
                            for (String w : worlds) {
                                if (matchesWorldKey(w, worldKey)) {
                                    alreadyPresent = true;
                                    break;
                                }
                            }
                            if (!alreadyPresent) {
                                worlds.add(worldKey.asString());
                                groups.set(targetGroup, worlds);
                            }
                        }
                        context.getPlugin().saveConfig();

                        // Hot-swap state for any online players in worldKey
                        World bw = Bukkit.getWorld(worldKey);
                        if (bw != null) {
                            for (Player p : bw.getPlayers()) {
                                PlayerStateSerializer.PlayerState newState = loadPlayerStateFromFile(targetId, p.getUniqueId());
                                context.getService(PlayerDataManager.class).getStateSerializer().applyPlayerState(p, bw, newState, true);
                                p.updateInventory();
                            }
                        }

                        syncFuture.complete(null);
                    } catch (Exception e) {
                        syncFuture.completeExceptionally(e);
                    } finally {
                        profileMigrationLock.writeLock().unlock();
                    }
                });
                return syncFuture;
            });
        });
    }

    public CompletableFuture<Void> removeWorldDataLink(NamespacedKey worldKey, boolean cloneData, java.util.function.DoubleConsumer progress) {
        String sourceId = getProfileId(worldKey);
        String targetId = getDefaultProfileId(worldKey);

        if (sourceId.equals(targetId)) return CompletableFuture.completedFuture(null);

        CompletableFuture<Void> preparePlayersFuture = new CompletableFuture<>();
        Runnable prepareTask = () -> {
            World bukkitWorld = Bukkit.getWorld(worldKey);
            if (bukkitWorld != null && !bukkitWorld.getPlayers().isEmpty()) {
                for (Player p : bukkitWorld.getPlayers()) {
                    context.getService(PlayerDataManager.class).savePlayerStateSync(p);
                }
            }
            preparePlayersFuture.complete(null);
        };

        if (Bukkit.isPrimaryThread()) prepareTask.run();
        else Bukkit.getScheduler().runTask(context.getPlugin(), prepareTask);

        return preparePlayersFuture.thenCompose(v -> CompletableFuture.runAsync(() -> {
            forceFlushPendingWrites();

            try (Connection conn = databaseService.getConnection()) {
                conn.setAutoCommit(false);
                try {
                    try (PreparedStatement delTarget = conn.prepareStatement(
                            "DELETE FROM voyage_player_profiles WHERE profile_id = ?;")) {
                        delTarget.setString(1, targetId);
                        delTarget.executeUpdate();
                    }
                    deleteProfileFolderOnDisk(targetId);

                    if (cloneData) {
                        String copySql = """
                            INSERT INTO voyage_player_profiles (profile_id, player_uuid, state_json, last_updated)
                            SELECT ?, player_uuid, state_json, ? FROM voyage_player_profiles WHERE profile_id = ?;
                        """;
                        try (PreparedStatement ps = conn.prepareStatement(copySql)) {
                            ps.setString(1, targetId);
                            ps.setLong(2, System.currentTimeMillis());
                            ps.setString(3, sourceId);
                            ps.executeUpdate();
                        }
                    }

                    conn.commit();
                    if (progress != null) progress.accept(1.0);
                } catch (SQLException ex) {
                    conn.rollback();
                    throw new CompletionException("Database error removing data link", ex);
                } finally {
                    conn.setAutoCommit(true);
                }
            } catch (SQLException e) {
                throw new CompletionException(e);
            }
        }, profileWriter)).thenCompose(v -> {
            CompletableFuture<Void> syncFuture = new CompletableFuture<>();
            Bukkit.getScheduler().runTask(context.getPlugin(), () -> {
                profileMigrationLock.writeLock().lock();
                try {
                    invalidateProfileCache();

                    ConfigurationSection groups = context.getPlugin().getConfig().getConfigurationSection("world-groups");
                    if (groups != null) {
                        for (String group : groups.getKeys(false)) {
                            List<String> worlds = groups.getStringList(group);
                            boolean removed = worlds.removeIf(w -> matchesWorldKey(w, worldKey));
                            if (removed) {
                                groups.set(group, worlds.isEmpty() ? null : worlds);
                                if (worlds.isEmpty()) {
                                    deleteProfileRecordsAsync("group:" + group);
                                }
                            }
                        }
                    }
                    context.getPlugin().saveConfig();

                    World bw = Bukkit.getWorld(worldKey);
                    if (bw != null) {
                        for (Player p : bw.getPlayers()) {
                            PlayerStateSerializer.PlayerState newState = cloneData ? loadPlayerStateFromFile(targetId, p.getUniqueId()) : null;
                            context.getService(PlayerDataManager.class).getStateSerializer().applyPlayerState(p, bw, newState, true);
                            p.updateInventory();
                        }
                    }

                    syncFuture.complete(null);
                } catch (Exception e) {
                    syncFuture.completeExceptionally(e);
                } finally {
                    profileMigrationLock.writeLock().unlock();
                }
            });
            return syncFuture;
        });
    }

    public CompletableFuture<Void> cloneWorldData(@NotNull NamespacedKey sourceKey, @NotNull NamespacedKey targetKey, java.util.function.DoubleConsumer progress) {
        String sourceId = getProfileId(sourceKey);
        String targetId = getProfileId(targetKey);
        if (sourceId.equals(targetId)) return CompletableFuture.completedFuture(null);

        CompletableFuture<Void> preparePlayersFuture = new CompletableFuture<>();
        Runnable prepareTask = () -> {
            try {
                for (Player p : List.copyOf(Bukkit.getOnlinePlayers())) {
                    if (getProfileId(p.getWorld().getKey()).equals(sourceId)) {
                        context.getService(PlayerDataManager.class).savePlayerStateSync(p);
                    }
                }
            } catch (Exception ignored) {}
            preparePlayersFuture.complete(null);
        };

        if (Bukkit.isPrimaryThread()) prepareTask.run();
        else {
            try {
                if (Bukkit.getScheduler() != null) {
                    Bukkit.getScheduler().runTask(context.getPlugin(), prepareTask);
                } else {
                    prepareTask.run();
                }
            } catch (Exception e) {
                prepareTask.run();
            }
        }

        return preparePlayersFuture.thenCompose(v -> CompletableFuture.runAsync(() -> {
            forceFlushPendingWrites();

            String cloneSql = """
                INSERT INTO voyage_player_profiles (profile_id, player_uuid, state_json, last_updated)
                SELECT ?, player_uuid, state_json, ? FROM voyage_player_profiles WHERE profile_id = ?
                ON CONFLICT(profile_id, player_uuid) DO UPDATE SET
                    state_json = excluded.state_json,
                    last_updated = excluded.last_updated;
            """;

            try (Connection conn = databaseService.getConnection();
                 PreparedStatement ps = conn.prepareStatement(cloneSql)) {
                ps.setString(1, targetId);
                ps.setLong(2, System.currentTimeMillis());
                ps.setString(3, sourceId);
                ps.executeUpdate();
                if (progress != null) progress.accept(1.0);
            } catch (SQLException e) {
                throw new CompletionException("Failed to clone player data via SQLite", e);
            }
        }, profileWriter));
    }

    public Optional<VoyageAPI.OfflineLocation> getPlayerLogOffLocation(NamespacedKey worldKey, UUID playerUuid) {
        String profileId = getProfileId(worldKey);
        PlayerStateSerializer.PlayerState state = loadPlayerStateFromFile(profileId, playerUuid);
        if (state != null) {
            String keyStr = worldKey.asString();
            if (state.worldLocations != null) {
                for (Map.Entry<String, PlayerStateSerializer.LocationData> entry : state.worldLocations.entrySet()) {
                    if (entry.getKey() != null && (entry.getKey().equals(keyStr)
                            || (worldKey.getNamespace().equals(NamespacedKey.MINECRAFT) && entry.getKey().equals(worldKey.getKey()))
                            || worldKey.equals(NamespacedKey.fromString(entry.getKey())))) {
                        PlayerStateSerializer.LocationData loc = entry.getValue();
                        if (loc != null) {
                            return Optional.of(new VoyageAPI.OfflineLocation(
                                    worldKey, loc.x, loc.y, loc.z, loc.yaw, loc.pitch));
                        }
                    }
                }
            }
            if (state.lastLocWorld != null && (state.lastLocWorld.equals(keyStr)
                    || (worldKey.getNamespace().equals(NamespacedKey.MINECRAFT) && state.lastLocWorld.equals(worldKey.getKey())))) {
                return Optional.of(new VoyageAPI.OfflineLocation(
                        worldKey, state.lastLocX, state.lastLocY, state.lastLocZ,
                        state.lastLocYaw, state.lastLocPitch));
            }
        }
        return Optional.empty();
    }

    /**
     * Loads every stored player state belonging to a world's data profile.
     * Used by the vanilla world export to package playerdata.
     */
    public @NotNull Map<UUID, PlayerStateSerializer.PlayerState> loadAllPlayerStates(@NotNull NamespacedKey worldKey) {
        String profileId = getProfileId(worldKey);
        Map<UUID, PlayerStateSerializer.PlayerState> states = new LinkedHashMap<>();

        try (Connection conn = databaseService.getConnection();
             PreparedStatement ps = conn.prepareStatement(
                     "SELECT player_uuid, state_json FROM voyage_player_profiles WHERE profile_id = ?;")) {
            ps.setString(1, profileId);
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    try {
                        UUID uuid = UUID.fromString(rs.getString("player_uuid"));
                        String json = rs.getString("state_json");
                        states.put(uuid, gson.fromJson(json, PlayerStateSerializer.PlayerState.class));
                    } catch (Exception ignored) {}
                }
            }
        } catch (SQLException e) {
            context.getPlugin().getLogger().log(Level.SEVERE, "Failed to load states for export on profile: " + profileId, e);
        }

        // Overlay pending in-memory writes (fresher than DB)
        for (Map.Entry<ProfileKey, PlayerStateSerializer.PlayerState> entry : pendingWrites.entrySet()) {
            if (entry.getKey().profileId().equals(profileId)) {
                states.put(entry.getKey().uuid(), entry.getValue());
            }
        }

        return states;
    }
}