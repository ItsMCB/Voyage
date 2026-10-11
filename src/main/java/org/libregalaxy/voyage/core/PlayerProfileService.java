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

import java.io.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.nio.file.attribute.BasicFileAttributes;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.locks.ReentrantReadWriteLock;

public class PlayerProfileService {

    private final VoyageContext context;
    private final Gson gson;
    private final File dataFolder;

    private record ProfileKey(String profileId, UUID uuid) {}

    private final Map<ProfileKey, PlayerStateSerializer.PlayerState> pendingWrites = new ConcurrentHashMap<>();
    private final Set<ProfileKey> scheduledWrites = ConcurrentHashMap.newKeySet();
    private final Thread shutdownHook;

    private final ExecutorService profileWriter = Executors.newSingleThreadExecutor(r -> new Thread(r, "Voyage-Profile-Writer"));

    private final Map<NamespacedKey, String> profileIdCache = new ConcurrentHashMap<>();
    private final ReentrantReadWriteLock profileMigrationLock = new ReentrantReadWriteLock();

    public static final int CURRENT_SCHEMA = 1;

    public PlayerProfileService(VoyageContext context) {
        this.context = context;
        this.gson = new Gson();
        this.dataFolder = new File(context.getPlugin().getDataFolder(), "playerdata");
        if (!dataFolder.exists()) dataFolder.mkdirs();

        this.shutdownHook = new Thread(() -> {
            profileWriter.shutdown();
            try {
                if (!profileWriter.awaitTermination(5, TimeUnit.SECONDS)) {
                    context.getPlugin().getLogger().warning("Profile writer flush timed out. Forcing flush.");
                    forceFlushPendingWrites();
                }
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
        }, "voyage-profile-shutdown");
        Runtime.getRuntime().addShutdownHook(shutdownHook);
    }

    public void shutdown() {
        try {
            Runtime.getRuntime().removeShutdownHook(shutdownHook);
        } catch (IllegalStateException ignored) {}

        profileWriter.shutdown();
        try {
            if (!profileWriter.awaitTermination(5, TimeUnit.SECONDS)) {
                forceFlushPendingWrites();
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }

    void forceFlushPendingWrites() {
        for (Map.Entry<ProfileKey, PlayerStateSerializer.PlayerState> entry : pendingWrites.entrySet()) {
            ProfileKey key = entry.getKey();
            PlayerStateSerializer.PlayerState toWrite = pendingWrites.get(key);
            if (toWrite != null) {
                try {
                    savePlayerStateToFile(key.profileId(), key.uuid(), toWrite);
                } catch (Exception e) {
                    context.getPlugin().getLogger().severe("Failed to force-flush data for " + key.uuid() + ": " + e.getMessage());
                } finally {
                    pendingWrites.remove(key, toWrite);
                }
            }
        }
        scheduledWrites.clear();
    }

    public void loadAll() {
        if (!dataFolder.exists()) dataFolder.mkdirs();
    }

    public void reload() {
        invalidateProfileCache();
        saveAllOnlineAsync();
    }

    public void invalidateProfileCache() {
        profileIdCache.clear();
    }

    private File getProfileFolder(String profileId) {
        return new File(dataFolder, "profiles" + File.separator + profileId.replace(":", "_"));
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
        if (key.getNamespace().equals(NamespacedKey.MINECRAFT) && entry.equalsIgnoreCase(key.getKey())) {
            return true;
        }
        return false;
    }

    private PlayerStateSerializer.PlayerState readPlayerStateDirectlyFromFile(File file, UUID uuid) {
        if (!file.exists()) return null;
        try (InputStreamReader reader = new InputStreamReader(new FileInputStream(file), StandardCharsets.UTF_8)) {
            return gson.fromJson(reader, PlayerStateSerializer.PlayerState.class);
        } catch (Exception e) {
            return null;
        }
    }

    private void savePlayerStateToFile(String profileId, UUID uuid, PlayerStateSerializer.PlayerState state) {
        File dir = getProfileFolder(profileId);
        if (!dir.exists()) dir.mkdirs();
        File file = new File(dir, uuid.toString() + ".json");
        File temp = new File(dir, uuid.toString() + ".json.tmp");

        // Preserve previously recorded locations for other worlds in the same profile group
        if (state.worldLocations == null) {
            state.worldLocations = new HashMap<>();
        }
        if (state.lastLocWorld != null) {
            state.worldLocations.put(state.lastLocWorld, new PlayerStateSerializer.LocationData(
                    state.lastLocX, state.lastLocY, state.lastLocZ, state.lastLocYaw, state.lastLocPitch));
        }
        if (file.exists()) {
            PlayerStateSerializer.PlayerState existing = readPlayerStateDirectlyFromFile(file, uuid);
            if (existing != null && existing.worldLocations != null) {
                for (Map.Entry<String, PlayerStateSerializer.LocationData> entry : existing.worldLocations.entrySet()) {
                    state.worldLocations.putIfAbsent(entry.getKey(), entry.getValue());
                }
            }
        }

        try (OutputStreamWriter writer = new OutputStreamWriter(new FileOutputStream(temp), StandardCharsets.UTF_8)) {
            gson.toJson(state, writer);
        } catch (IOException e) {
            throw new CompletionException("Failed to stream JSON for " + uuid, e);
        }

        try {
            Files.move(temp.toPath(), file.toPath(), StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
        } catch (AtomicMoveNotSupportedException e) {
            try {
                Files.move(temp.toPath(), file.toPath(), StandardCopyOption.REPLACE_EXISTING);
            } catch (IOException ex) {
                throw new CompletionException("Failed fallback move for " + uuid, ex);
            }
        } catch (IOException e) {
            throw new CompletionException("Failed to execute move for " + uuid, e);
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
                        savePlayerStateToFile(profileId, uuid, toWrite);
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
                            savePlayerStateToFile(profileId, uuid, toWrite);
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
            savePlayerStateToFile(profileId, uuid, state);
        } finally {
            pendingWrites.remove(key, state);
        }
    }

    public PlayerStateSerializer.PlayerState loadPlayerStateFromFile(String profileId, UUID uuid) {
        ProfileKey key = new ProfileKey(profileId, uuid);

        PlayerStateSerializer.PlayerState pending = pendingWrites.get(key);
        if (pending != null) return pending;

        File file = new File(getProfileFolder(profileId), uuid.toString() + ".json");
        if (!file.exists()) return null;

        try (InputStreamReader reader = new InputStreamReader(new FileInputStream(file), StandardCharsets.UTF_8)) {
            PlayerStateSerializer.PlayerState state = gson.fromJson(reader, PlayerStateSerializer.PlayerState.class);
            if (state != null && state.schemaVersion != CURRENT_SCHEMA) {
                context.getPlugin().getLogger().warning("Player state for " + uuid + " has schema version " + state.schemaVersion + ", expected " + CURRENT_SCHEMA);
            }
            return state;
        } catch (Exception e) {
            context.getPlugin().getLogger().severe("Failed to parse player data for " + uuid);
            return null;
        }
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
                            File oldGroupDir = getProfileFolder("group:" + group);
                            profileWriter.submit(() -> deleteDirectory(oldGroupDir));
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
        pendingWrites.keySet().removeIf(k -> k.profileId().equals(defaultProfileId));
        scheduledWrites.removeIf(k -> k.profileId().equals(defaultProfileId));
        profileWriter.submit(() -> deleteDirectory(getProfileFolder(defaultProfileId)));
    }

    private void deleteDirectory(File dir) {
        if (!dir.exists()) return;
        try {
            Files.walkFileTree(dir.toPath(), new SimpleFileVisitor<>() {
                @Override
                public FileVisitResult visitFile(Path file, BasicFileAttributes attrs) throws IOException {
                    Files.delete(file);
                    return FileVisitResult.CONTINUE;
                }
                @Override
                public FileVisitResult postVisitDirectory(Path dir, IOException exc) throws IOException {
                    Files.delete(dir);
                    return FileVisitResult.CONTINUE;
                }
            });
        } catch (IOException ignored) {}
    }

    public VoyageAPI.DataLinkPreview previewProfileMerge(NamespacedKey worldKey, String targetProfileIdRaw, VoyageAPI.DataLinkMergeStrategy strategy) {
        String sourceId = getProfileId(worldKey);
        String targetId;
        if (targetProfileIdRaw == null || targetProfileIdRaw.equalsIgnoreCase("none") || targetProfileIdRaw.equals(getDefaultProfileId(worldKey))) {
            targetId = getDefaultProfileId(worldKey);
        } else if (targetProfileIdRaw.startsWith("group:")) {
            targetId = targetProfileIdRaw;
        } else if (targetProfileIdRaw.startsWith("isolated:")) {
            targetId = targetProfileIdRaw;
        } else {
            targetId = "group:" + targetProfileIdRaw;
        }

        VoyageAPI.DataLinkMergeStrategy effectiveStrategy = (strategy != null) ? strategy : VoyageAPI.DataLinkMergeStrategy.DESTINATION_PRIORITY;

        if (sourceId.equals(targetId)) return new VoyageAPI.DataLinkPreview(0, 0, 0, effectiveStrategy);

        File sourceDir = getProfileFolder(sourceId);
        File targetDir = getProfileFolder(targetId);

        Set<String> sourceUuids = new HashSet<>();
        if (sourceDir.exists()) {
            String[] sourceFiles = sourceDir.list((d, n) -> n.endsWith(".json"));
            if (sourceFiles != null) {
                for (String file : sourceFiles) sourceUuids.add(file.replace(".json", ""));
            }
        }

        try {
            World sourceWorld = Bukkit.getWorld(worldKey);
            if (sourceWorld != null) {
                for (Player p : sourceWorld.getPlayers()) sourceUuids.add(p.getUniqueId().toString());
            }
        } catch (Exception ignored) {}

        for (ProfileKey pk : pendingWrites.keySet()) {
            if (pk.profileId().equals(sourceId)) {
                sourceUuids.add(pk.uuid().toString());
            }
        }

        Set<String> targetUuids = new HashSet<>();
        if (targetDir.exists()) {
            String[] targetFiles = targetDir.list((d, n) -> n.endsWith(".json"));
            if (targetFiles != null) {
                for (String file : targetFiles) targetUuids.add(file.replace(".json", ""));
            }
        }

        for (ProfileKey pk : pendingWrites.keySet()) {
            if (pk.profileId().equals(targetId)) {
                targetUuids.add(pk.uuid().toString());
            }
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

            // Synchronously capture online players in this world without kicking them
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

                File sourceDir = getProfileFolder(sourceId);
                File targetDir = getProfileFolder(targetId);

                if (targetId.startsWith("isolated:") && targetDir.exists()) {
                    deleteDirectory(targetDir);
                }
                if (!targetDir.exists()) targetDir.mkdirs();

                if (sourceDir.exists()) {
                    File[] sourceFiles = sourceDir.listFiles((d, n) -> n.endsWith(".json"));
                    if (sourceFiles != null && sourceFiles.length > 0) {
                        int total = sourceFiles.length;
                        int count = 0;
                        for (File sf : sourceFiles) {
                            File tf = new File(targetDir, sf.getName());
                            if (!tf.exists()) {
                                try {
                                    Files.copy(sf.toPath(), tf.toPath(), StandardCopyOption.REPLACE_EXISTING);
                                } catch (IOException e) {
                                    throw new CompletionException(new IOException("Failed to merge data file: " + sf.getName(), e));
                                }
                            } else {
                                try {
                                    PlayerStateSerializer.PlayerState sourceState;
                                    try (InputStreamReader reader = new InputStreamReader(new FileInputStream(sf), StandardCharsets.UTF_8)) {
                                        sourceState = gson.fromJson(reader, PlayerStateSerializer.PlayerState.class);
                                    }
                                    PlayerStateSerializer.PlayerState targetState;
                                    try (InputStreamReader reader = new InputStreamReader(new FileInputStream(tf), StandardCharsets.UTF_8)) {
                                        targetState = gson.fromJson(reader, PlayerStateSerializer.PlayerState.class);
                                    }

                                    if (sourceState != null && targetState != null) {
                                        PlayerStateSerializer.PlayerState primaryState =
                                                (effectiveStrategy == VoyageAPI.DataLinkMergeStrategy.SOURCE_PRIORITY) ? sourceState : targetState;
                                        PlayerStateSerializer.PlayerState secondaryState =
                                                (effectiveStrategy == VoyageAPI.DataLinkMergeStrategy.SOURCE_PRIORITY) ? targetState : sourceState;

                                        // Merge offline locations from both profiles so positions are preserved
                                        if (secondaryState.worldLocations != null) {
                                            if (primaryState.worldLocations == null) {
                                                primaryState.worldLocations = new HashMap<>();
                                            }
                                            for (Map.Entry<String, PlayerStateSerializer.LocationData> entry : secondaryState.worldLocations.entrySet()) {
                                                primaryState.worldLocations.putIfAbsent(entry.getKey(), entry.getValue());
                                            }
                                        }
                                        if (secondaryState.lastLocWorld != null && primaryState.worldLocations != null) {
                                            primaryState.worldLocations.putIfAbsent(secondaryState.lastLocWorld, new PlayerStateSerializer.LocationData(
                                                    secondaryState.lastLocX, secondaryState.lastLocY, secondaryState.lastLocZ, secondaryState.lastLocYaw, secondaryState.lastLocPitch));
                                        }

                                        File tempFile = new File(targetDir, tf.getName() + ".tmp");
                                        try (OutputStreamWriter writer = new OutputStreamWriter(new FileOutputStream(tempFile), StandardCharsets.UTF_8)) {
                                            gson.toJson(primaryState, writer);
                                        }
                                        Files.move(tempFile.toPath(), tf.toPath(), StandardCopyOption.REPLACE_EXISTING);
                                    } else if (effectiveStrategy == VoyageAPI.DataLinkMergeStrategy.SOURCE_PRIORITY) {
                                        Files.copy(sf.toPath(), tf.toPath(), StandardCopyOption.REPLACE_EXISTING);
                                    }
                                } catch (IOException e) {
                                    if (effectiveStrategy == VoyageAPI.DataLinkMergeStrategy.SOURCE_PRIORITY) {
                                        try {
                                            Files.copy(sf.toPath(), tf.toPath(), StandardCopyOption.REPLACE_EXISTING);
                                        } catch (IOException ex) {
                                            throw new CompletionException(new IOException("Failed to merge data file: " + sf.getName(), ex));
                                        }
                                    }
                                }
                            }
                            count++;
                            if (progress != null) progress.accept((double) count / total);
                        }
                    } else if (progress != null) progress.accept(1.0);
                } else if (progress != null) progress.accept(1.0);

                if (sourceId.startsWith("isolated:") && sourceDir.exists()) {
                    deleteDirectory(sourceDir);
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
                                    File oldGroupDir = getProfileFolder("group:" + group);
                                    profileWriter.submit(() -> deleteDirectory(oldGroupDir));
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

            File sourceDir = getProfileFolder(sourceId);
            File targetDir = getProfileFolder(targetId);

            if (targetDir.exists()) deleteDirectory(targetDir);
            targetDir.mkdirs();

            if (cloneData && sourceDir.exists()) {
                File[] sourceFiles = sourceDir.listFiles((d, n) -> n.endsWith(".json"));
                if (sourceFiles != null && sourceFiles.length > 0) {
                    int total = sourceFiles.length;
                    int count = 0;
                    for (File sf : sourceFiles) {
                        try {
                            Files.copy(sf.toPath(), new File(targetDir, sf.getName()).toPath(), StandardCopyOption.REPLACE_EXISTING);
                        } catch (IOException e) {
                            throw new CompletionException(new IOException("Failed to clone data file: " + sf.getName(), e));
                        }
                        count++;
                        if (progress != null) progress.accept((double) count / total);
                    }
                } else if (progress != null) progress.accept(1.0);
            } else if (progress != null) {
                progress.accept(1.0);
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
                                    File oldGroupDir = getProfileFolder("group:" + group);
                                    profileWriter.submit(() -> deleteDirectory(oldGroupDir));
                                }
                            }
                        }
                    }
                    context.getPlugin().saveConfig();

                    // Hot-swap state for online players in worldKey
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

            File sourceDir = getProfileFolder(sourceId);
            File targetDir = getProfileFolder(targetId);
            if (!targetDir.exists()) targetDir.mkdirs();

            if (sourceDir.exists()) {
                File[] sourceFiles = sourceDir.listFiles((d, n) -> n.endsWith(".json"));
                if (sourceFiles != null && sourceFiles.length > 0) {
                    int total = sourceFiles.length;
                    int count = 0;
                    for (File sf : sourceFiles) {
                        try {
                            Files.copy(sf.toPath(), new File(targetDir, sf.getName()).toPath(), StandardCopyOption.REPLACE_EXISTING);
                        } catch (IOException e) {
                            throw new CompletionException(new IOException("Failed to clone data file: " + sf.getName(), e));
                        }
                        count++;
                        if (progress != null) progress.accept((double) count / total);
                    }
                } else if (progress != null) progress.accept(1.0);
            } else if (progress != null) progress.accept(1.0);
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
     * Loads every stored player state belonging to a world's data profile —
     * the on-disk profile folder plus any pending writes (which are always
     * fresher than the files). Used by the vanilla world export to build
     * playerdata entries for every player who has played in the world.
     */
    public @NotNull Map<UUID, PlayerStateSerializer.PlayerState> loadAllPlayerStates(@NotNull NamespacedKey worldKey) {
        String profileId = getProfileId(worldKey);
        Map<UUID, PlayerStateSerializer.PlayerState> states = new LinkedHashMap<>();
        Set<UUID> uuids = new LinkedHashSet<>();

        // Players with unsaved-in-flight state (e.g. just evacuated)
        for (ProfileKey pending : pendingWrites.keySet()) {
            if (pending.profileId().equals(profileId)) uuids.add(pending.uuid());
        }

        File dir = getProfileFolder(profileId);
        if (dir.exists()) {
            String[] files = dir.list((d, name) -> name.endsWith(".json"));
            if (files != null) {
                for (String fileName : files) {
                    try {
                        uuids.add(UUID.fromString(fileName.replace(".json", "")));
                    } catch (IllegalArgumentException ignored) {}
                }
            }
        }

        for (UUID uuid : uuids) {
            // loadPlayerStateFromFile already prefers pendingWrites over disk
            PlayerStateSerializer.PlayerState state = loadPlayerStateFromFile(profileId, uuid);
            if (state != null) states.put(uuid, state);
        }
        return states;
    }
}