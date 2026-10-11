package org.libregalaxy.voyage.core;

import dev.dejvokep.boostedyaml.YamlDocument;
import dev.dejvokep.boostedyaml.block.implementation.Section;
import dev.dejvokep.boostedyaml.route.Route;
import dev.dejvokep.boostedyaml.settings.dumper.DumperSettings;
import dev.dejvokep.boostedyaml.settings.general.GeneralSettings;
import dev.dejvokep.boostedyaml.settings.loader.LoaderSettings;
import dev.dejvokep.boostedyaml.settings.updater.UpdaterSettings;
import io.papermc.paper.event.world.WorldDifficultyChangeEvent;
import net.kyori.adventure.nbt.CompoundBinaryTag;
import net.kyori.adventure.text.Component;
import org.bukkit.Bukkit;
import org.bukkit.Difficulty;
import org.bukkit.GameRule;
import org.bukkit.NamespacedKey;
import org.bukkit.Registry;
import org.bukkit.World;
import org.bukkit.WorldType;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.world.WorldUnloadEvent;
import org.bukkit.plugin.java.JavaPlugin;
import org.bukkit.scheduler.BukkitTask;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;
import org.libregalaxy.voyage.VoyageContext;
import org.libregalaxy.voyage.VoyagePlugin;
import org.libregalaxy.voyage.api.ManagedWorld;
import org.libregalaxy.voyage.api.VoyageAPI;
import org.libregalaxy.voyage.api.builder.WorldBuilder;
import org.libregalaxy.voyage.api.events.VoyageWorldCloneEvent;
import org.libregalaxy.voyage.api.events.VoyageWorldDeleteEvent;
import org.libregalaxy.voyage.api.events.VoyageWorldUnloadEvent;
import org.libregalaxy.voyage.api.portal.CoordinateStrategy;
import org.libregalaxy.voyage.api.portal.PortalGenerator;
import org.libregalaxy.voyage.api.portal.PortalLink;
import org.libregalaxy.voyage.api.portal.PortalType;
import org.libregalaxy.voyage.api.portal.generator.NoneGenerator;
import org.libregalaxy.voyage.api.portal.generator.StructureGenerator;
import org.libregalaxy.voyage.api.portal.generator.VanillaNetherGenerator;
import org.libregalaxy.voyage.api.portal.strategy.FixedSpawnStrategy;
import org.libregalaxy.voyage.api.portal.strategy.VanillaScalingStrategy;
import org.libregalaxy.voyage.core.migration.LevelDatNbt;
import org.libregalaxy.voyage.core.migration.VanillaPlayerData;
import org.libregalaxy.voyage.core.migration.VanillaPlayerStateConverter;
import org.libregalaxy.voyage.core.migration.VanillaWorldExporter;

import java.io.File;
import java.io.IOException;
import java.nio.file.*;
import java.nio.file.attribute.BasicFileAttributes;
import java.util.*;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.concurrent.ConcurrentHashMap;
import java.util.logging.Level;
import java.util.stream.Collectors;

public class VoyageWorldManager implements VoyageAPI, Listener {

    private final VoyageContext context;
    private final JavaPlugin plugin;
    private final Map<NamespacedKey, ManagedWorldImpl> registry = new ConcurrentHashMap<>();
    private final Set<NamespacedKey> buildingWorlds = ConcurrentHashMap.newKeySet();
    private final Set<NamespacedKey> exportingWorlds = ConcurrentHashMap.newKeySet();

    public boolean isExporting(@NotNull NamespacedKey key) {
        return exportingWorlds.contains(key);
    }

    private final WorldDirectoryWatcher directoryWatcher;
    private final WorldImportService importService;
    private final WorldDiskScanner diskScanner;

    private final Object configLock = new Object();
    private volatile YamlDocument config;

    private volatile boolean loading = false;

    private final Object saveLock = new Object();
    private BukkitTask pendingSaveTask;
    private boolean dirty = false;

    private final Thread shutdownHook;

    public VoyageWorldManager(@NotNull VoyageContext context) {
        this.context = context;
        this.plugin  = context.getPlugin();

        try {
            this.config = YamlDocument.create(
                    new File(plugin.getDataFolder(), "worlds.yml"),
                    GeneralSettings.builder().setUseDefaults(false).build(),
                    LoaderSettings.builder().setAutoUpdate(false).build(),
                    DumperSettings.DEFAULT,
                    UpdaterSettings.DEFAULT);
        } catch (IOException e) {
            plugin.getLogger().log(Level.SEVERE, "Failed to initialise worlds.yml — world persistence is disabled!", e);
        }

        this.diskScanner = new WorldDiskScanner(plugin, this);
        this.importService = new WorldImportService(plugin, this);

        diskScanner.purgeOrphanedTempWorlds();

        shutdownHook = new Thread(() -> {
            boolean need;
            synchronized (saveLock) {
                need = dirty;
                dirty = false;
            }
            if (need) performSaveSync();
        }, "voyage-shutdown-save");
        Runtime.getRuntime().addShutdownHook(shutdownHook);

        loadState(false);

        this.directoryWatcher = new WorldDirectoryWatcher(plugin, this);
        this.directoryWatcher.start();
    }

    public void reload() {
        synchronized (configLock) {
            try { if (config != null) config.reload(); } catch (IOException ignored) {}
        }
        loadState(true);
        if (directoryWatcher != null) {
            directoryWatcher.populateInitialCache();
        }
        context.getPlayerDataManager().invalidateProfileCache();
    }

    public void shutdown() {
        if (directoryWatcher != null) {
            directoryWatcher.stop();
        }

        try { Runtime.getRuntime().removeShutdownHook(shutdownHook); }
        catch (IllegalStateException ignored) {}

        boolean need;
        synchronized (saveLock) {
            if (pendingSaveTask != null) {
                pendingSaveTask.cancel();
                pendingSaveTask = null;
            }
            need = dirty;
            dirty = false;
        }
        if (need) performSaveSync();
    }

    public JavaPlugin getPlugin() { return plugin; }
    public VoyageContext getContext() { return context; }

    public boolean claimBuild(NamespacedKey key) { return buildingWorlds.add(key); }
    public void releaseBuild(NamespacedKey key) { buildingWorlds.remove(key); }

    public void unregisterWorld(@NotNull NamespacedKey key) {
        registry.remove(key);
        saveState();
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onBukkitWorldUnload(@NotNull WorldUnloadEvent event) {
        ManagedWorldImpl mw = registry.get(event.getWorld().getKey());
        if (mw != null) {
            Bukkit.getPluginManager().callEvent(new VoyageWorldUnloadEvent(mw));
        }
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onWorldDifficultyChange(@NotNull WorldDifficultyChangeEvent event) {
        ManagedWorldImpl mw = registry.get(event.getWorld().getKey());
        if (mw != null && mw.getDifficulty() != event.getDifficulty()) {
            mw.setDifficultyInternal(event.getDifficulty());
            saveState();
        }
    }

    public CompletableFuture<Boolean> unload(@NotNull NamespacedKey worldKey, boolean save) {
        return unload(worldKey, null, save);
    }

    public CompletableFuture<Boolean> unload(@NotNull NamespacedKey worldKey, @Nullable NamespacedKey fallbackKey, boolean save) {
        World world = Bukkit.getWorld(worldKey);
        if (world == null) return CompletableFuture.completedFuture(true);

        List<CompletableFuture<Boolean>> teleports = new ArrayList<>();
        World fallback = fallbackKey != null && !fallbackKey.equals(worldKey) ? Bukkit.getWorld(fallbackKey) : null;
        if (fallback == null) fallback = determineEvacuationWorld(world);

        final World destination = fallback;
        for (Player player : List.copyOf(world.getPlayers())) {
            if (destination != null) {
                teleports.add(player.teleportAsync(destination.getSpawnLocation()).exceptionally(ex -> false));
            } else {
                player.kick(Component.text("This world is being unloaded and no fallback world is available."));
            }
        }

        var scheduler = Bukkit.getScheduler();
        return CompletableFuture.allOf(teleports.toArray(new CompletableFuture[0]))
                .thenComposeAsync(v -> {
                    CompletableFuture<Boolean> unloadFuture = new CompletableFuture<>();
                    if (!world.getPlayers().isEmpty()) {
                        plugin.getLogger().warning("Players still present in " + world.getKey() + " after evacuation — kicking remaining players.");
                        for (Player p : List.copyOf(world.getPlayers())) {
                            p.kick(Component.text("World evacuation failed. Please rejoin."));
                        }
                        scheduler.runTaskLater(plugin, () -> {
                            unloadFuture.complete(Bukkit.unloadWorld(world, save));
                        }, 1L);
                    } else {
                        scheduler.runTask(plugin, () -> {
                            unloadFuture.complete(Bukkit.unloadWorld(world, save));
                        });
                    }
                    return unloadFuture;
                }, runnable -> scheduler.runTask(plugin, runnable));
    }

    @Override
    public World getMainLevel() {
        return Bukkit.getWorlds().getFirst();
    }

    @Override
    public boolean worldExists(@NotNull NamespacedKey key) {
        if (Bukkit.getWorld(key) != null) return true;

        if (key.equals(NamespacedKey.minecraft("overworld"))) {
            Path levelDir = Bukkit.getServer().getLevelDirectory();
            return Files.exists(levelDir.resolve("region"))
                    || Files.exists(levelDir.resolve("paper-world.yml"))
                    || Files.exists(levelDir.resolve("level.dat"));
        }

        Path dim = Bukkit.getServer().getLevelDirectory()
                .resolve("dimensions")
                .resolve(key.getNamespace())
                .resolve(key.getKey());
        if (Files.isDirectory(dim)) {
            return Files.exists(dim.resolve("region"))
                    || Files.exists(dim.resolve("paper-world.yml"));
        }
        return false;
    }

    @Override
    public @NotNull Set<NamespacedKey> getAllWorlds() {
        Set<NamespacedKey> all = new HashSet<>();
        for (World w : Bukkit.getWorlds()) all.add(w.getKey());

        Path dimsDir = Bukkit.getServer().getLevelDirectory().resolve("dimensions");
        if (!Files.isDirectory(dimsDir)) return all;

        try (var namespaces = Files.list(dimsDir)) {
            namespaces.filter(Files::isDirectory).forEach(nsPath -> {
                String ns = nsPath.getFileName().toString();
                try (var keys = Files.list(nsPath)) {
                    keys.filter(Files::isDirectory).forEach(kPath -> {
                        if (Files.exists(kPath.resolve("region"))
                                || Files.exists(kPath.resolve("paper-world.yml"))) {
                            NamespacedKey k = NamespacedKey.fromString(ns + ":" + kPath.getFileName());
                            if (k != null) all.add(k);
                        }
                    });
                } catch (IOException ignored) {}
            });
        } catch (IOException e) {
            plugin.getLogger().log(Level.WARNING, "Failed to scan dimensions directory for existing worlds", e);
        }

        return all;
    }

    @Override public @NotNull Optional<ManagedWorld> getManagedWorld(@NotNull NamespacedKey key) { return Optional.ofNullable(registry.get(key)); }
    @Override public @NotNull List<ManagedWorld> getTrackedWorlds()  { return List.copyOf(registry.values()); }

    @Override public @NotNull List<ManagedWorld> getLoadedWorlds() {
        return registry.values().stream()
                .filter(ManagedWorld::isLoaded)
                .map(mw -> (ManagedWorld) mw)
                .collect(Collectors.toList());
    }

    @Override public List<String> getTrackedWorldNames()        { return registry.values().stream().map(mw -> mw.getKey().asString()).toList(); }
    @Override public List<String> getLoadedTrackedWorldNames()  { return registry.values().stream().filter(ManagedWorld::isLoaded).map(mw -> mw.getKey().asString()).toList(); }
    @Override public List<String> getOfflineTrackedWorldNames() { return registry.values().stream().filter(mw -> !mw.isLoaded()).map(mw -> mw.getKey().asString()).toList(); }

    @Override
    public List<String> getLoadedWorldNames() {
        Set<String> result = new LinkedHashSet<>();
        registry.values().stream().filter(ManagedWorld::isLoaded).forEach(mw -> result.add(mw.getKey().asString()));
        Bukkit.getWorlds().forEach(w -> result.add(w.getKey().asString()));
        return List.copyOf(result);
    }

    @Override
    public List<String> getAllWorldNames() {
        Set<String> result = new LinkedHashSet<>();
        registry.values().forEach(mw -> result.add(mw.getKey().asString()));
        Bukkit.getWorlds().forEach(w -> result.add(w.getKey().asString()));
        if (directoryWatcher != null) {
            result.addAll(directoryWatcher.getUnmanagedCache());
        }
        return List.copyOf(result);
    }

    public Set<String> scanDiskForUnmanagedWorlds() {
        return diskScanner.scanDiskForUnmanagedWorlds();
    }

    public void purgeOrphanedTempWorlds() {
        diskScanner.purgeOrphanedTempWorlds();
    }

    @Override public @NotNull WorldBuilder builder(@NotNull NamespacedKey key) { return new WorldBuilderImpl(key, this); }

    WorldBuilderImpl builderInternal(@NotNull NamespacedKey key) {
        WorldBuilderImpl b = new WorldBuilderImpl(key, this);
        b.setBypassValidation(true);
        return b;
    }

    @Override public String getWorldDataProfile(@NotNull NamespacedKey worldKey) { return context.getService(PlayerDataManager.class).getProfileId(worldKey); }
    @Override public DataLinkPreview previewWorldDataProfile(@NotNull NamespacedKey worldKey, String profileId, DataLinkMergeStrategy strategy) { return context.getService(PlayerDataManager.class).previewProfileMerge(worldKey, profileId, strategy); }

    @Override
    public CompletableFuture<Void> setWorldDataProfile(@NotNull NamespacedKey worldKey, String profileId, DataLinkMergeStrategy strategy, java.util.function.DoubleConsumer progress) {
        return context.getService(PlayerDataManager.class).setProfileId(worldKey, profileId, strategy, progress);
    }

    @Override
    public CompletableFuture<Void> breakWorldDataLink(@NotNull NamespacedKey worldKey, boolean cloneData, java.util.function.DoubleConsumer progress) {
        return context.getService(PlayerDataManager.class).removeWorldDataLink(worldKey, cloneData, progress);
    }

    @Override
    public Optional<OfflineLocation> getPlayerLogOffLocation(@NotNull NamespacedKey worldKey, java.util.UUID playerUuid) {
        return context.getService(PlayerDataManager.class).getPlayerLogOffLocation(worldKey, playerUuid);
    }

    @Override
    public CompletableFuture<Void> cloneWorldData(@NotNull NamespacedKey sourceKey, @NotNull NamespacedKey targetKey, @Nullable java.util.function.DoubleConsumer progress) {
        return context.getService(PlayerDataManager.class).cloneWorldData(sourceKey, targetKey, progress);
    }

    @Override
    public CompletableFuture<ManagedWorld> cloneWorld(
            @NotNull NamespacedKey sourceKey,
            @NotNull NamespacedKey targetKey,
            boolean clonePlayerData,
            @Nullable java.util.function.DoubleConsumer progress) {

        if (sourceKey.equals(targetKey)) {
            return CompletableFuture.failedFuture(new IllegalArgumentException("Source and target worlds cannot be the same."));
        }

        Optional<ManagedWorld> optSource = getManagedWorld(sourceKey);
        if (optSource.isEmpty()) {
            return CompletableFuture.failedFuture(new IllegalArgumentException("Source world " + sourceKey.asString() + " is not managed by Voyage."));
        }

        if (worldExists(targetKey)) {
            return CompletableFuture.failedFuture(new IllegalStateException("Target world " + targetKey.asString() + " already exists."));
        }

        ManagedWorld source = optSource.get();
        World sourceWorld = Bukkit.getWorld(sourceKey);
        boolean wasSourceLoaded = sourceWorld != null;

        if (sourceWorld != null && getMainLevel().equals(sourceWorld)) {
            return CompletableFuture.failedFuture(new IllegalStateException("The main server level cannot be safely unloaded for cloning."));
        }

        CompletableFuture<Boolean> unloadFuture = wasSourceLoaded
                ? unload(sourceKey, true)
                : CompletableFuture.completedFuture(true);

        return unloadFuture.thenCompose(sourceUnloaded -> {
            if (!sourceUnloaded) {
                return CompletableFuture.failedFuture(new IllegalStateException("Could not safely unmount source world " + sourceKey.asString()));
            }

            WorldBuilder builder = builder(targetKey)
                    .environment(source.getEnvironment())
                    .seed(source.getSeed())
                    .generator(source.getGenerator().orElse(null))
                    .type(source.getWorldType())
                    .generatorSettings(source.getGeneratorSettings().orElse(null))
                    .difficulty(source.getDifficulty())
                    .generateStructures(source.isGenerateStructures())
                    .autoLoad(true)
                    .fromTemplate(sourceKey);

            source.getConfiguredSpawn().ifPresent(s -> builder.spawn(s[0], s[1], s[2]));
            for (Map.Entry<GameRule<?>, Object> entry : source.getGameRules().entrySet()) {
                applyGameRuleToBuilder(builder, entry.getKey(), entry.getValue());
            }
            builder.metadata(source.getAllMetadata());

            return builder.create().thenCompose(mw -> {
                Bukkit.getPluginManager().callEvent(new VoyageWorldCloneEvent(mw));
                if (clonePlayerData) {
                    return cloneWorldData(sourceKey, targetKey, progress).thenApply(v -> mw);
                } else {
                    return CompletableFuture.completedFuture(mw);
                }
            }).whenComplete((mw, ex) -> {
                if (wasSourceLoaded) {
                    getManagedWorld(sourceKey).ifPresent(ManagedWorld::load);
                }
            });
        });
    }

    @Override
    public CompletableFuture<Boolean> deleteWorld(@NotNull NamespacedKey key, @Nullable NamespacedKey fallbackWorldKey) {
        if (key.equals(getMainLevel().getKey()) || key.equals(Bukkit.getWorlds().getFirst().getKey())) {
            plugin.getLogger().severe("Prevented attempt to delete the main server level.");
            return CompletableFuture.completedFuture(false);
        }

        exportingWorlds.add(key);

        ManagedWorldImpl mw = registry.get(key);
        if (mw == null) {
            exportingWorlds.remove(key);
            return CompletableFuture.completedFuture(false);
        }

        CompletableFuture<Boolean> result = new CompletableFuture<>();

        Bukkit.getScheduler().runTask(plugin, () -> {
            VoyageWorldDeleteEvent event = new VoyageWorldDeleteEvent(mw);
            Bukkit.getPluginManager().callEvent(event);
            if (event.isCancelled()) {
                exportingWorlds.remove(key);
                result.complete(false);
                return;
            }

            unload(key, fallbackWorldKey, false).thenAccept(unloaded -> {
                if (!unloaded) {
                    plugin.getLogger().warning("Bukkit refused to unload world '" + key + "'. Deletion aborted.");
                    exportingWorlds.remove(key);
                    result.complete(false);
                    return;
                }

                File folder = resolveWorldFolder(key);

                if (!folder.exists()) {
                    finalizeWorldRemoval(key);
                    exportingWorlds.remove(key);
                    result.complete(true);
                    return;
                }

                deleteDirectory(folder).whenComplete((v, ex) -> {
                    exportingWorlds.remove(key);
                    if (ex != null) {
                        plugin.getLogger().warning("Filesystem deletion failed for " + key + ": " + ex.getMessage());
                        result.complete(false);
                    } else {
                        finalizeWorldRemoval(key);
                        result.complete(true);
                    }
                });
            }).exceptionally(ex -> {
                exportingWorlds.remove(key);
                plugin.getLogger().log(Level.SEVERE, "Unexpected error during unload phase for " + key, ex);
                result.complete(false);
                return null;
            });
        });

        return result;
    }

    private void finalizeWorldRemoval(@NotNull NamespacedKey key) {
        registry.remove(key);

        for (ManagedWorldImpl mw : registry.values()) {
            for (PortalType type : PortalType.values()) {
                mw.getPortalLink(type).ifPresent(link -> {
                    if (link.targetWorld().equals(key)) {
                        mw.unlinkPortal(type);
                    }
                });
            }
        }

        saveState();
        context.getService(PlayerDataManager.class).deleteWorldData(key);

        if (directoryWatcher != null) {
            directoryWatcher.getUnmanagedCache().remove(key.asString());
        }

        Runnable fireEvent = () -> Bukkit.getPluginManager().callEvent(new org.libregalaxy.voyage.api.events.VoyageWorldDeletedEvent(key));
        if (Bukkit.isPrimaryThread()) {
            fireEvent.run();
        } else {
            Bukkit.getScheduler().runTask(plugin, fireEvent);
        }
    }

    private void deleteDirectoryWithRetry(Path path, int attemptsLeft, CompletableFuture<Void> future) {
        Bukkit.getScheduler().runTaskAsynchronously(plugin, () -> {
            try {
                if (Files.exists(path)) {
                    Files.walkFileTree(path, new SimpleFileVisitor<>() {
                        @Override
                        public FileVisitResult visitFile(Path file, BasicFileAttributes attrs) throws IOException {
                            Files.deleteIfExists(file);
                            return FileVisitResult.CONTINUE;
                        }

                        @Override
                        public FileVisitResult postVisitDirectory(Path dir, IOException exc) throws IOException {
                            if (exc != null) throw exc;
                            Files.deleteIfExists(dir);
                            return FileVisitResult.CONTINUE;
                        }
                    });
                }
                future.complete(null);
            } catch (IOException e) {
                if (attemptsLeft > 1) {
                    Bukkit.getScheduler().runTaskLater(plugin, () -> {
                        deleteDirectoryWithRetry(path, attemptsLeft - 1, future);
                    }, 5L);
                } else {
                    future.completeExceptionally(e);
                }
            }
        });
    }

    @Override
    public CompletableFuture<Void> untrackWorld(@NotNull NamespacedKey key) {
        World world = Bukkit.getWorld(key);
        CompletableFuture<Boolean> unloadFuture = world != null ? unload(key, true) : CompletableFuture.completedFuture(true);

        return unloadFuture.thenAccept(success -> {
            if (!success) {
                throw new CompletionException(new IllegalStateException("Failed to safely unload world before untracking: " + key));
            }

            registry.remove(key);
            saveState();
            if (directoryWatcher != null) {
                directoryWatcher.getUnmanagedCache().add(key.asString());
            }

            context.getService(PlayerDataManager.class).removeWorldFromGroups(key);
        });
    }

    @Nullable
    public World determineEvacuationWorld(@NotNull World current) {
        String defaultName = plugin.getConfig().getString("default-world");
        if (defaultName != null && !defaultName.isBlank()) {
            NamespacedKey k = NamespacedKey.fromString(defaultName);
            World w = k != null ? Bukkit.getWorld(k) : Bukkit.getWorld(defaultName);
            if (w != null && !w.equals(current)) return w;
        }
        List<World> candidates = new ArrayList<>(Bukkit.getWorlds());
        candidates.remove(current);
        return candidates.isEmpty() ? null : candidates.getFirst();
    }

    boolean registerNewWorld(@NotNull ManagedWorldImpl world) {
        if (registry.putIfAbsent(world.getKey(), world) != null) return false;
        saveState();
        if (directoryWatcher != null) {
            directoryWatcher.getUnmanagedCache().remove(world.getKey().asString());
        }
        return true;
    }

    protected World loadBukkitWorld(@NotNull ManagedWorldImpl mw) {
        File worldFolder = resolveWorldFolder(mw.getKey());
        if (worldFolder.exists()) {
            ensureWorldGenFiles(mw.getKey(), worldFolder, mw);
        }

        boolean isVoid = VoyageAPI.isVoidWorld(mw.getGenerator().orElse(null), mw.getGeneratorSettings().orElse(null));

        var creator = org.bukkit.WorldCreator.ofKey(mw.getKey());
        creator.environment(mw.getEnvironment());
        creator.seed(mw.getSeed());
        creator.type(isVoid ? WorldType.FLAT : mw.getWorldType());
        creator.generateStructures(!isVoid && mw.isGenerateStructures());

        if (isVoid) {
            creator.generatorSettings(VoyageAPI.VANILLA_VOID_SETTINGS);
            creator.generator(VoyagePlugin.VOID_CHUNK_GENERATOR);
            creator.biomeProvider(VoyagePlugin.VOID_BIOME_PROVIDER);
        } else {
            mw.getGeneratorSettings().ifPresent(creator::generatorSettings);
            mw.getGenerator().ifPresent(creator::generator);
        }

        World world = creator.createWorld();
        if (world != null) {
            world.setDifficulty(mw.getDifficulty());

            int[] spawnOverride = mw.getSpawnOverride();
            if (spawnOverride != null) {
                world.setSpawnLocation(spawnOverride[0], spawnOverride[1], spawnOverride[2]);
            }

            mw.applyGameRulesTo(world);
        }
        return world;
    }

    // ------------------------------------------------------------- exporting

    @Override
    public CompletableFuture<Void> exportWorld(@NotNull NamespacedKey worldKey, @NotNull Path targetZipFile) {
        return exportWorld(worldKey, targetZipFile, null, null);
    }

    @Override
    public CompletableFuture<Void> exportWorld(@NotNull NamespacedKey worldKey, @NotNull Path targetZipFile,
                                               @Nullable UUID primaryPlayer, @Nullable String displayName) {
        if (worldKey.equals(Bukkit.getWorlds().getFirst().getKey())) {
            return CompletableFuture.failedFuture(new IllegalStateException("Cannot export the main level."));
        }

        exportingWorlds.add(worldKey);

        World world = Bukkit.getWorld(worldKey);
        boolean wasLoaded = world != null;

        VanillaPlayerData.LastPosition livePosition = snapshotLivePosition(primaryPlayer, world);
        VanillaWorldExporter.WorldBorderData liveBorder = captureWorldBorder(world);

        Map<UUID, CompoundBinaryTag> serverStates = new LinkedHashMap<>();
        if (world != null) {
            for (Player p : List.copyOf(world.getPlayers())) {
                try {
                    p.saveData();
                    CompoundBinaryTag state = captureServerPlayerState(p, world);
                    if (state != null) {
                        serverStates.put(p.getUniqueId(), state);
                    } else {
                        plugin.getLogger().warning("Could not locate the server player data file for "
                                + p.getName() + " — their export will use profile conversion.");
                    }
                } catch (Throwable t) {
                    plugin.getLogger().warning("Could not snapshot player data for "
                            + p.getName() + ": " + t.getMessage());
                }
            }
        }

        CompletableFuture<Boolean> unloadFuture = wasLoaded
                ? unload(worldKey, Bukkit.getWorlds().getFirst().getKey(), true)
                : CompletableFuture.completedFuture(true);

        return unloadFuture.thenCompose(unloaded -> {
            if (!unloaded) {
                return CompletableFuture.failedFuture(new IllegalStateException("Failed to unload world for export."));
            }

            List<VanillaPlayerData.ExportedPlayer> players =
                    collectPlayerDataForExport(worldKey, primaryPlayer, livePosition, serverStates);

            return CompletableFuture.runAsync(() -> {
                try {
                    File worldDir = resolveWorldFolder(worldKey);
                    ManagedWorldImpl mw = registry.get(worldKey);
                    if (mw != null) {
                        ensureWorldGenFiles(worldKey, worldDir, mw);
                    }

                    if (liveBorder != null) {
                        patchWorldBorder(worldDir.toPath(), liveBorder);
                    }

                    boolean voidWorld = mw != null && VoyageAPI.isVoidWorld(
                            mw.getGenerator().orElse(null),
                            mw.getGeneratorSettings().orElse(null));

                    String exportName = displayName != null && !displayName.isBlank()
                            ? displayName
                            : worldKey.asString().replace(':', '_');

                    new VanillaWorldExporter(plugin.getLogger()).export(
                            worldDir.toPath(),
                            worldKey.asString().replace(':', '_'),
                            exportName,
                            voidWorld,
                            players,
                            primaryPlayer,
                            targetZipFile);
                } catch (IOException e) {
                    throw new CompletionException("Failed to export world to zip", e);
                }
            }, r -> Bukkit.getScheduler().runTaskAsynchronously(plugin, r));
        }).whenComplete((v, ex) -> {
            exportingWorlds.remove(worldKey);
            if (wasLoaded) {
                getManagedWorld(worldKey).ifPresent(ManagedWorld::load);
            }
        });
    }

    private @Nullable VanillaPlayerData.LastPosition snapshotLivePosition(
            @Nullable UUID primaryPlayer, @Nullable World world) {
        if (primaryPlayer == null || world == null) return null;
        Player online = Bukkit.getPlayer(primaryPlayer);
        if (online == null || !online.getWorld().equals(world)) return null;
        var loc = online.getLocation();
        return new VanillaPlayerData.LastPosition(
                loc.getX(), loc.getY(), loc.getZ(), loc.getYaw(), loc.getPitch());
    }

    private @Nullable VanillaWorldExporter.WorldBorderData captureWorldBorder(@Nullable World world) {
        if (world == null) return null;
        try {
            var border = world.getWorldBorder();
            return new VanillaWorldExporter.WorldBorderData(
                    border.getCenter().getX(),
                    border.getCenter().getZ(),
                    border.getSize(),
                    border.getDamageAmount(),
                    border.getDamageBuffer(),
                    border.getWarningDistance(),
                    border.getWarningTimeTicks());
        } catch (Throwable t) {
            return null;
        }
    }

    private void patchWorldBorder(@NotNull Path worldDir,
                                  @NotNull VanillaWorldExporter.WorldBorderData border) {
        try {
            Path levelDat = worldDir.resolve("level.dat");
            if (!Files.isRegularFile(levelDat)) return;
            CompoundBinaryTag root = LevelDatNbt.read(levelDat);
            LevelDatNbt.writeVanillaSafe(levelDat, VanillaWorldExporter.applyWorldBorder(root, border), null);
        } catch (Exception e) {
            plugin.getLogger().warning("Could not persist the live world border for export: " + e.getMessage());
        }
    }

    private @NotNull List<VanillaPlayerData.ExportedPlayer> collectPlayerDataForExport(
            @NotNull NamespacedKey worldKey, @Nullable UUID primaryPlayer,
            @Nullable VanillaPlayerData.LastPosition livePosition,
            @NotNull Map<UUID, CompoundBinaryTag> serverStates) {
        List<VanillaPlayerData.ExportedPlayer> players = new ArrayList<>();
        int fromServer = 0;
        int fromProfile = 0;
        try {
            PlayerDataManager playerDataManager = context.getService(PlayerDataManager.class);
            Map<UUID, PlayerStateSerializer.PlayerState> states = playerDataManager.getStoredPlayerStates(worldKey);

            for (Map.Entry<UUID, PlayerStateSerializer.PlayerState> entry : states.entrySet()) {
                UUID uuid = entry.getKey();
                PlayerStateSerializer.PlayerState state = entry.getValue();
                try {
                    CompoundBinaryTag serverState = serverStates.get(uuid);

                    CompoundBinaryTag converted;
                    VanillaPlayerData.LastPosition position;
                    if (serverState != null) {
                        converted = serverState;
                        position = null;
                        fromServer++;
                    } else {
                        converted = VanillaPlayerStateConverter.toVanillaPlayerTag(state, worldKey.asString());
                        position = (livePosition != null && uuid.equals(primaryPlayer))
                                ? livePosition
                                : resolveStoredPosition(playerDataManager, worldKey, uuid);
                        if (state.inventory != null && !converted.keySet().contains("Inventory")) {
                            plugin.getLogger().warning("Exported player " + uuid
                                    + " without items (inventory empty or unreadable). Item blob head: "
                                    + hexPreview(state.inventory));
                        }
                        fromProfile++;
                    }

                    players.add(new VanillaPlayerData.ExportedPlayer(uuid, resolvePlayerName(uuid), converted, position));
                } catch (Exception perPlayer) {
                    plugin.getLogger().warning("Could not export player data for " + uuid + ": " + perPlayer);
                }
            }
        } catch (Exception e) {
            plugin.getLogger().warning("Could not collect Voyage player data for export of " + worldKey + ": " + e.getMessage());
        }
        if (!players.isEmpty()) {
            plugin.getLogger().info("Export player data: " + fromServer + " from server serialization, "
                    + fromProfile + " from profile conversion (" + players.size() + " total).");
        }
        return players;
    }

    private @Nullable CompoundBinaryTag captureServerPlayerState(@NotNull Player player, @NotNull World world) {
        String fileName = player.getUniqueId() + ".dat";
        Path[] candidates = {
                world.getWorldFolder().toPath().resolve("playerdata").resolve(fileName),
                world.getWorldFolder().toPath().resolve("players").resolve("data").resolve(fileName),
                Bukkit.getServer().getLevelDirectory().resolve("playerdata").resolve(fileName),
                Bukkit.getServer().getLevelDirectory().resolve("players").resolve("data").resolve(fileName)
        };
        for (Path candidate : candidates) {
            if (Files.isRegularFile(candidate)) {
                try {
                    return LevelDatNbt.read(candidate);
                } catch (Exception unreadable) {
                    plugin.getLogger().warning("Could not read server player data " + candidate + ": " + unreadable);
                }
            }
        }
        return null;
    }

    private @Nullable VanillaPlayerData.LastPosition resolveStoredPosition(
            @NotNull PlayerDataManager playerDataManager, @NotNull NamespacedKey worldKey, @NotNull UUID uuid) {
        return playerDataManager.getPlayerLogOffLocation(worldKey, uuid)
                .map(loc -> new VanillaPlayerData.LastPosition(loc.x(), loc.y(), loc.z(), loc.yaw(), loc.pitch()))
                .orElse(null);
    }

    private @Nullable String resolvePlayerName(@NotNull UUID uuid) {
        try {
            return Bukkit.getOfflinePlayer(uuid).getName();
        } catch (Throwable ignored) {
            return null;
        }
    }

    private static @Nullable String hexPreview(@Nullable String base64) {
        if (base64 == null || base64.isBlank()) return "n/a";
        try {
            byte[] bytes = Base64.getDecoder().decode(base64);
            StringBuilder hex = new StringBuilder();
            for (int i = 0; i < Math.min(16, bytes.length); i++) {
                hex.append(String.format("%02x ", bytes[i]));
            }
            return hex.toString().trim();
        } catch (Exception e) {
            return "n/a";
        }
    }

    // ------------------------------------------------------- world gen files

    public void ensureWorldGenFiles(@NotNull NamespacedKey worldKey, @NotNull File worldDir, @NotNull ManagedWorldImpl mw) {
        Path dimDir = worldDir.toPath();
        Path levelDat = dimDir.resolve("level.dat");
        Path wgsFile = dimDir.resolve("data").resolve("minecraft").resolve("world_gen_settings.dat");

        boolean hasChunks = Files.isDirectory(dimDir.resolve("region"))
                || Files.isDirectory(dimDir.resolve("entities"))
                || Files.isDirectory(dimDir.resolve("poi"));

        if (Files.isRegularFile(levelDat) && (hasChunks || Files.isRegularFile(wgsFile))) {
            repairLevelDatIfNeeded(levelDat);
            return;
        }

        boolean isVoid = VoyageAPI.isVoidWorld(mw.getGenerator().orElse(null), mw.getGeneratorSettings().orElse(null));

        WorldImportService.DimensionGenInfo genInfo = new WorldImportService.DimensionGenInfo(
                isVoid ? WorldType.FLAT : mw.getWorldType(),
                isVoid ? VoyageAPI.VANILLA_VOID_SETTINGS : mw.getGeneratorSettings().orElse(null),
                isVoid ? (plugin.getName() + ":void") : mw.getGenerator().orElse(null),
                mw.getEnvironment(),
                mw.getSeed(),
                !isVoid && mw.isGenerateStructures()
        );

        WorldImportService.ParsedWorldData fallbackData = new WorldImportService.ParsedWorldData(
                null,
                LevelDatNbt.serverDataVersion(),
                mw.getSeed(),
                !isVoid && mw.isGenerateStructures(),
                Map.of(worldKey.asString(), genInfo)
        );

        importService.writeDimensionGenFiles(dimDir, worldKey, genInfo, fallbackData);
    }

    private void repairLevelDatIfNeeded(@NotNull Path levelDat) {
        try {
            CompoundBinaryTag root = LevelDatNbt.read(levelDat);
            CompoundBinaryTag data = LevelDatNbt.getData(root);
            boolean healthy = LevelDatNbt.resolveDataVersion(root, 0) > 0
                    && data.getInt(LevelDatNbt.FORMAT_VERSION_KEY, 0) > 0;
            if (healthy) return;
            LevelDatNbt.writeVanillaSafe(levelDat, root, null);
            plugin.getLogger().info("Repaired missing DataVersion/format version in " + levelDat);
        } catch (IOException e) {
            plugin.getLogger().warning("Could not verify level.dat at " + levelDat + ": " + e.getMessage());
        }
    }

    @NotNull
    public File resolveWorldFolder(@NotNull NamespacedKey key) {
        World loaded = Bukkit.getWorld(key);
        if (loaded != null) return loaded.getWorldFolder();

        if (key.equals(NamespacedKey.minecraft("overworld"))) {
            return Bukkit.getServer().getLevelDirectory().toFile();
        }

        return Bukkit.getServer().getLevelDirectory()
                .resolve("dimensions")
                .resolve(key.getNamespace())
                .resolve(key.getKey())
                .toFile();
    }

    public void copyDirectory(@NotNull File source, @NotNull File target) throws IOException {
        Path src = source.toPath(), tgt = target.toPath();
        if (!Files.exists(src)) return;

        Files.walkFileTree(src, new SimpleFileVisitor<>() {
            @Override
            public FileVisitResult preVisitDirectory(Path dir, BasicFileAttributes attrs) throws IOException {
                if (dir.getFileName().toString().equals("dimensions") && dir.getParent().equals(src)) {
                    return FileVisitResult.SKIP_SUBTREE;
                }
                Path targetDir = tgt.resolve(src.relativize(dir));
                if (!Files.exists(targetDir)) Files.createDirectories(targetDir);
                return FileVisitResult.CONTINUE;
            }

            @Override
            public FileVisitResult visitFile(Path file, BasicFileAttributes attrs) throws IOException {
                String name = file.getFileName().toString();
                Path parent = file.getParent();
                Path grandParent = parent != null ? parent.getParent() : null;

                boolean isPaperMetadata = name.equals("metadata.dat")
                        && parent != null && parent.getFileName().toString().equals("paper")
                        && grandParent != null && grandParent.getFileName().toString().equals("data");

                if (!name.equals("uid.dat") && !name.equals("session.lock") && !isPaperMetadata) {
                    Files.copy(file, tgt.resolve(src.relativize(file)), StandardCopyOption.REPLACE_EXISTING);
                }
                return FileVisitResult.CONTINUE;
            }
        });
    }

    public CompletableFuture<Void> deleteDirectory(@NotNull File folder) {
        if (!folder.exists()) return CompletableFuture.completedFuture(null);

        CompletableFuture<Void> future = new CompletableFuture<>();
        deleteDirectoryWithRetry(folder.toPath(), 3, future);
        return future;
    }

    protected void saveState() {
        if (loading || config == null) return;
        synchronized (saveLock) {
            dirty = true;
            if (pendingSaveTask != null) return;
            pendingSaveTask = Bukkit.getScheduler().runTaskLaterAsynchronously(plugin, () -> {
                boolean shouldSave;
                synchronized (saveLock) {
                    pendingSaveTask = null;
                    shouldSave = dirty;
                    dirty = false;
                }
                if (shouldSave) performSaveSync();
            }, 100L);
        }
    }

    private void performSaveSync() {
        synchronized (configLock) {
            config.remove("worlds");
            for (ManagedWorldImpl mw : registry.values()) {
                if (mw.isTemporary()) continue;
                Route base = Route.from("worlds", mw.getKey().asString());

                boolean isVoid = VoyageAPI.isVoidWorld(mw.getGenerator().orElse(null), mw.getGeneratorSettings().orElse(null));

                config.set(base.add("environment"),       mw.getEnvironment().name());
                config.set(base.add("seed"),              mw.getSeed());
                config.set(base.add("generator"),         isVoid ? (plugin.getName() + ":void") : mw.getGenerator().orElse(null));
                config.set(base.add("worldType"),         isVoid ? WorldType.FLAT.name() : mw.getWorldType().name());
                config.set(base.add("generatorSettings"), isVoid ? VoyageAPI.VANILLA_VOID_SETTINGS : mw.getGeneratorSettings().orElse(null));
                config.set(base.add("autoLoad"),          mw.isAutoLoad());

                config.set(base.add("difficulty"),         mw.getDifficulty().name());
                config.set(base.add("generateStructures"), !isVoid && mw.isGenerateStructures());

                int[] spawnOverride = mw.getSpawnOverride();
                if (spawnOverride != null) {
                    config.set(base.add("spawn").add("x"), spawnOverride[0]);
                    config.set(base.add("spawn").add("y"), spawnOverride[1]);
                    config.set(base.add("spawn").add("z"), spawnOverride[2]);
                } else {
                    config.remove(base.add("spawn"));
                }

                Map<GameRule<?>, Object> rules = mw.getGameRules();
                if (rules.isEmpty()) {
                    config.remove(base.add("gamerules"));
                } else {
                    Route grBase = base.add("gamerules");
                    for (Map.Entry<GameRule<?>, Object> entry : rules.entrySet()) {
                        String ruleName = entry.getKey().getKey().value();
                        config.set(grBase.add(ruleName), String.valueOf(entry.getValue()));
                    }
                }

                Map<String, String> meta = mw.getAllMetadata();
                if (meta.isEmpty()) {
                    config.remove(base.add("metadata"));
                } else {
                    Route metaBase = base.add("metadata");
                    for (Map.Entry<String, String> entry : meta.entrySet()) {
                        config.set(metaBase.add(entry.getKey()), entry.getValue());
                    }
                }

                for (PortalType type : PortalType.values()) {
                    mw.getPortalLink(type).ifPresent(link -> {
                        Route lp = base.add("links").add(type.name());
                        config.set(lp.add("target"), link.targetWorld().asString());

                        if (link.coordinateStrategy() instanceof VanillaScalingStrategy vs) {
                            config.set(lp.add("strategy").add("type"),   "VANILLA_SCALING");
                            config.set(lp.add("strategy").add("factor"), vs.getScaleFactor());
                        } else if (link.coordinateStrategy() instanceof FixedSpawnStrategy) {
                            config.set(lp.add("strategy").add("type"), "TARGET_SPAWN_POINT");
                        }

                        if (link.generator() instanceof VanillaNetherGenerator) {
                            config.set(lp.add("generator").add("type"), "VANILLA_NETHER");
                        } else if (link.generator() instanceof StructureGenerator sg) {
                            config.set(lp.add("generator").add("type"),         "STRUCTURE");
                            config.set(lp.add("generator").add("structureKey"), sg.getStructureKey().asString());
                        } else if (link.generator() instanceof NoneGenerator) {
                            config.set(lp.add("generator").add("type"), "NONE");
                        }
                    });
                }
            }
            try { config.save(); }
            catch (IOException e) { plugin.getLogger().severe("Failed to save worlds.yml!"); }
        }
    }

    private void loadState(boolean isReload) {
        if (config == null) return;

        synchronized (this) {
            if (loading) {
                plugin.getLogger().warning("loadState invoked while a previous load is still in progress — skipping.");
                return;
            }
            loading = true;
        }

        List<CompletableFuture<ManagedWorld>> pending = new ArrayList<>();
        Set<NamespacedKey> successfullyProcessed = ConcurrentHashMap.newKeySet();

        try {
            synchronized (configLock) {
                Section worldsSection = config.getSection("worlds");
                if (worldsSection != null) {
                    for (Object keyObj : worldsSection.getKeys()) {
                        String keyStr = keyObj.toString();
                        NamespacedKey key = NamespacedKey.fromString(keyStr);
                        Section wSec = worldsSection.getSection(Route.from(keyStr));

                        if (key == null) {
                            plugin.getLogger().warning("Invalid world key '" + keyStr + "' in worlds.yml — skipping.");
                            continue;
                        }
                        if (wSec.getBoolean("temporary", false)) {
                            plugin.getLogger().warning("Temporary world '" + keyStr + "' found in worlds.yml — skipping.");
                            continue;
                        }
                        successfullyProcessed.add(key);
                        boolean preExisting = isReload && registry.containsKey(key);
                        ManagedWorldImpl existing = preExisting ? registry.get(key) : null;

                        String generator = wSec.getString("generator", null);
                        String generatorSettings = wSec.getString("generatorSettings", null);
                        boolean isVoid = VoyageAPI.isVoidWorld(generator, generatorSettings);

                        WorldBuilderImpl builder = preExisting ? null : builderInternal(key);
                        if (builder != null) {
                            WorldType worldType = isVoid ? WorldType.FLAT : WorldType.NORMAL;
                            if (!isVoid) {
                                String worldTypeName = wSec.getString("worldType", "NORMAL");
                                try {
                                    worldType = WorldType.valueOf(worldTypeName);
                                } catch (IllegalArgumentException e) {
                                    plugin.getLogger().warning("Unknown WorldType '" + worldTypeName + "' for world " + key + ". Defaulting to NORMAL.");
                                }
                            }

                            World.Environment env = World.Environment.NORMAL;
                            String envName = wSec.getString("environment", "NORMAL");
                            try {
                                env = World.Environment.valueOf(envName);
                            } catch (IllegalArgumentException e) {
                                plugin.getLogger().warning("Unknown Environment '" + envName + "' for world " + key + ". Defaulting to NORMAL.");
                            }

                            builder.environment(env)
                                    .seed(wSec.getLong("seed", 0L))
                                    .generator(isVoid ? (plugin.getName() + ":void") : generator)
                                    .type(worldType)
                                    .generatorSettings(isVoid ? VoyageAPI.VANILLA_VOID_SETTINGS : generatorSettings)
                                    .autoLoad(wSec.getBoolean("autoLoad", true));
                        }

                        Difficulty difficulty = Difficulty.NORMAL;
                        String diffStr = wSec.getString("difficulty", "NORMAL");
                        try {
                            difficulty = Difficulty.valueOf(diffStr);
                        } catch (IllegalArgumentException e) {
                            plugin.getLogger().warning("Unknown difficulty '" + diffStr + "' for world " + key + ". Defaulting to NORMAL.");
                        }

                        boolean generateStructures = !isVoid && wSec.getBoolean("generateStructures", true);

                        int[] spawn = null;
                        Section spawnSec = wSec.getSection("spawn");
                        if (spawnSec != null) {
                            spawn = new int[]{
                                    spawnSec.getInt("x"),
                                    spawnSec.getInt("y"),
                                    spawnSec.getInt("z")
                            };
                        }

                        Map<GameRule<?>, Object> gameRules = new LinkedHashMap<>();
                        Section grSec = wSec.getSection("gamerules");
                        if (grSec != null) {
                            for (Object grKeyObj : grSec.getKeys()) {
                                String ruleName = grKeyObj.toString();
                                String snakeCaseRule = ruleName.replaceAll("([a-z])([A-Z]+)", "$1_$2").toLowerCase(Locale.ROOT);
                                NamespacedKey ruleKey = NamespacedKey.fromString(snakeCaseRule);
                                if (ruleKey == null) continue;
                                GameRule<?> rule = Registry.GAME_RULE.get(ruleKey);
                                if (rule == null) continue;
                                String valStr = grSec.getString(ruleName);
                                Object val = parseGameRuleValue(rule, valStr);
                                if (val != null) gameRules.put(rule, val);
                            }
                        }

                        Map<String, String> metadata = new LinkedHashMap<>();
                        Section metaSec = wSec.getSection("metadata");
                        if (metaSec != null) {
                            for (Object mKeyObj : metaSec.getKeys()) {
                                String mKey = mKeyObj.toString();
                                String mVal = metaSec.getString(mKey);
                                if (mVal != null) metadata.put(mKey, mVal);
                            }
                        }

                        if (existing != null) {
                            existing.setAutoLoad(wSec.getBoolean("autoLoad", true));
                            existing.clearLinks();
                            existing.setDifficultyInternal(difficulty);
                            existing.setSpawnInternal(spawn);
                            existing.clearGameRulesInternal();
                            existing.setGameRulesInternal(gameRules);
                            existing.setMetadataInternal(metadata);
                            if (isVoid) {
                                existing.setWorldType(WorldType.FLAT);
                                existing.setGeneratorSettings(VoyageAPI.VANILLA_VOID_SETTINGS);
                                existing.setGenerator(plugin.getName() + ":void");
                            }
                        }

                        if (builder != null) {
                            builder.difficulty(difficulty)
                                    .generateStructures(generateStructures);
                            if (spawn != null) builder.spawn(spawn[0], spawn[1], spawn[2]);
                            for (Map.Entry<GameRule<?>, Object> entry : gameRules.entrySet()) {
                                applyGameRuleToBuilder(builder, entry.getKey(), entry.getValue());
                            }
                            builder.metadata(metadata);
                        }

                        Section linksSec = wSec.getSection("links");
                        if (linksSec != null) {
                            for (Object ltObj : linksSec.getKeys()) {
                                String typeStr = ltObj.toString();
                                PortalType type;
                                try { type = PortalType.valueOf(typeStr); }
                                catch (IllegalArgumentException e) { continue; }

                                Section lSec = linksSec.getSection(typeStr);
                                NamespacedKey target = NamespacedKey.fromString(lSec.getString("target", ""));
                                if (target == null) continue;

                                CoordinateStrategy strategy;
                                if ("VANILLA_SCALING".equals(lSec.getString("strategy.type"))) {
                                    double f = lSec.getDouble("strategy.factor", 1.0);
                                    strategy = new VanillaScalingStrategy(f <= 0.0 ? 1.0 : f);
                                } else {
                                    strategy = new FixedSpawnStrategy();
                                }

                                PortalGenerator generatorInstance;
                                String genType = lSec.getString("generator.type", "NONE");
                                if ("STRUCTURE".equals(genType)) {
                                    NamespacedKey sk = NamespacedKey.fromString(lSec.getString("generator.structureKey", ""));
                                    generatorInstance = sk != null ? new StructureGenerator(sk) : new NoneGenerator();
                                } else if ("VANILLA_NETHER".equals(genType)) {
                                    generatorInstance = new VanillaNetherGenerator();
                                } else {
                                    generatorInstance = new NoneGenerator();
                                }

                                PortalLink link = new PortalLink(target, strategy, generatorInstance);
                                if (existing != null) existing.linkPortal(type, link);
                                else if (builder != null) builder.linkPortal(type, link);
                            }
                        }

                        if (!preExisting && builder != null) {
                            pending.add(builder.create().handle((mw, ex) -> {
                                if (ex != null) {
                                    Throwable cause = ex instanceof CompletionException ? ex.getCause() : ex;
                                    plugin.getLogger().log(Level.WARNING, "Failed to load '" + key + "': " + cause.getMessage());
                                    successfullyProcessed.remove(key);
                                    return null;
                                }
                                return mw;
                            }));
                        }
                    }
                }

                try {
                    ConfigurationSection groups = plugin.getConfig().getConfigurationSection("world-groups");
                    if (groups != null) {
                        for (String group : groups.getKeys(false)) {
                            for (String wKeyStr : groups.getStringList(group)) {
                                NamespacedKey key = NamespacedKey.fromString(wKeyStr);
                                if (key == null || successfullyProcessed.contains(key) || (isReload && registry.containsKey(key))) continue;
                                successfullyProcessed.add(key);

                                World.Environment env = World.Environment.NORMAL;
                                String kv = key.getKey().toLowerCase(Locale.ROOT);
                                if (kv.equals("the_nether") || kv.endsWith("_nether"))        env = World.Environment.NETHER;
                                else if (kv.equals("the_end") || kv.endsWith("_end"))         env = World.Environment.THE_END;

                                plugin.getLogger().info("Auto-importing data-linked dimension: " + wKeyStr);

                                WorldBuilderImpl b = builderInternal(key);
                                b.importExisting(true).autoLoad(true).environment(env);

                                pending.add(b.create().handle((mw, ex) -> {
                                    if (ex != null) {
                                        Throwable cause = ex instanceof CompletionException ? ex.getCause() : ex;
                                        plugin.getLogger().log(Level.SEVERE, "Failed to auto-import '" + key + "': " + cause.getMessage());
                                        successfullyProcessed.remove(key);
                                        return null;
                                    }
                                    return mw;
                                }));
                            }
                        }
                    }
                } catch (Exception e) {
                    plugin.getLogger().severe("Exception auto-importing world-groups: " + e.getMessage());
                }
            }
        } catch (Exception e) {
            plugin.getLogger().log(Level.SEVERE, "Exception parsing worlds.yml: " + e.getMessage(), e);
            loading = false;
            return;
        }

        Runnable onComplete = () -> {
            loading = false;
            if (isReload) {
                Set<NamespacedKey> toRemove = new HashSet<>(registry.keySet());
                toRemove.removeAll(successfullyProcessed);
                for (NamespacedKey keyToRemove : toRemove) {
                    registry.remove(keyToRemove);
                    World w = Bukkit.getWorld(keyToRemove);
                    if (w != null) {
                        Bukkit.getScheduler().runTask(plugin, () -> {
                            if (!Bukkit.unloadWorld(w, true)) {
                                plugin.getLogger().warning("Failed to unload untracked world " + keyToRemove + " during reload.");
                            }
                        });
                    }
                }
            }
            saveState();
        };

        if (pending.isEmpty()) {
            onComplete.run();
        } else {
            CompletableFuture.allOf(pending.toArray(new CompletableFuture[0]))
                    .whenComplete((v, ex) -> {
                        if (ex != null) plugin.getLogger().log(Level.SEVERE, "Unexpected error during world load batch", ex);
                        onComplete.run();
                    });
        }
    }

    @Override
    public CompletableFuture<ImportStrategy> importWorld(
            @NotNull Path sourceDir,
            @NotNull NamespacedKey targetKey,
            boolean autoLink,
            @Nullable String dataProfileId,
            @Nullable DataLinkMergeStrategy mergeStrategy,
            boolean deleteSourceAfterImport,
            @Nullable World.Environment environment,
            @Nullable String generator,
            @Nullable String generatorSettings
    ) {
        return importService.importWorld(sourceDir, targetKey, autoLink, dataProfileId, mergeStrategy, deleteSourceAfterImport, environment, generator, generatorSettings);
    }

    @SuppressWarnings("unchecked")
    private static Object parseGameRuleValue(GameRule<?> rule, String valStr) {
        if (valStr == null) return null;
        if (rule.getType() == Boolean.class) {
            return Boolean.parseBoolean(valStr);
        } else if (rule.getType() == Integer.class) {
            try {
                return Integer.parseInt(valStr);
            } catch (NumberFormatException e) {
                return null;
            }
        }
        return null;
    }

    @SuppressWarnings("unchecked")
    private static <T> void applyGameRuleToBuilder(WorldBuilder builder, GameRule<T> rule, Object value) {
        builder.gameRule(rule, (T) value);
    }
}