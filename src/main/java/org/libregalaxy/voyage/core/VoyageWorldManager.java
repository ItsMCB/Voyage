package org.libregalaxy.voyage.core;

import net.kyori.adventure.text.Component;
import org.bukkit.Bukkit;
import org.bukkit.NamespacedKey;
import org.bukkit.World;
import org.bukkit.WorldType;
import org.bukkit.entity.Player;
import org.bukkit.plugin.java.JavaPlugin;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;
import org.libregalaxy.voyage.VoyageContext;
import org.libregalaxy.voyage.VoyagePlugin;
import org.libregalaxy.voyage.api.ManagedWorld;
import org.libregalaxy.voyage.api.VoyageAPI;
import org.libregalaxy.voyage.api.builder.WorldBuilder;
import org.libregalaxy.voyage.api.events.VoyageWorldCloneEvent;
import org.libregalaxy.voyage.api.events.VoyageWorldDeleteEvent;
import org.libregalaxy.voyage.api.events.VoyageWorldDeletedEvent;
import org.libregalaxy.voyage.api.portal.PortalType;

import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.*;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.concurrent.ConcurrentHashMap;
import java.util.logging.Level;
import java.util.stream.Collectors;

public class VoyageWorldManager implements VoyageAPI {

    private final VoyageContext context;
    private final JavaPlugin plugin;
    final Map<NamespacedKey, ManagedWorldImpl> registry = new ConcurrentHashMap<>();
    private final Set<NamespacedKey> buildingWorlds = ConcurrentHashMap.newKeySet();

    private final WorldFileService fileService;
    private final WorldConfigService configService;
    private final WorldExportService exportService;
    private final WorldImportService importService;
    private final WorldDiskScanner diskScanner;
    private final WorldDirectoryWatcher directoryWatcher;

    public VoyageWorldManager(@NotNull VoyageContext context) {
        this.context = context;
        this.plugin  = context.getPlugin();

        this.fileService = new WorldFileService(plugin);
        this.configService = new WorldConfigService(plugin, this);
        this.exportService = new WorldExportService(context, this, fileService);
        this.diskScanner = new WorldDiskScanner(plugin, this);
        this.importService = new WorldImportService(plugin, this);

        diskScanner.purgeOrphanedTempWorlds();
        configService.loadState(false);

        this.directoryWatcher = new WorldDirectoryWatcher(plugin, this);
        this.directoryWatcher.start();
    }

    public void reload() {
        configService.reload();
        if (directoryWatcher != null) {
            directoryWatcher.populateInitialCache();
        }
        context.getPlayerDataManager().invalidateProfileCache();
    }

    public void shutdown() {
        if (directoryWatcher != null) {
            directoryWatcher.stop();
        }
        configService.shutdown();
    }

    public JavaPlugin getPlugin() { return plugin; }
    public VoyageContext getContext() { return context; }
    public Map<NamespacedKey, ManagedWorldImpl> getRegistry() { return registry; }
    public WorldDirectoryWatcher getDirectoryWatcher() { return directoryWatcher; }

    public boolean claimBuild(NamespacedKey key) { return buildingWorlds.add(key); }
    public void releaseBuild(NamespacedKey key) { buildingWorlds.remove(key); }
    public boolean isExporting(@NotNull NamespacedKey key) { return exportService.isExporting(key); }

    public void unregisterWorld(@NotNull NamespacedKey key) {
        registry.remove(key);
        saveState();
    }

    protected void saveState() {
        configService.saveState();
    }

    @NotNull
    public File resolveWorldFolder(@NotNull NamespacedKey key) {
        return fileService.resolveWorldFolder(key);
    }

    public void copyDirectory(@NotNull File source, @NotNull File target) throws IOException {
        fileService.copyDirectory(source, target);
    }

    public CompletableFuture<Void> deleteDirectory(@NotNull File folder) {
        return fileService.deleteDirectory(folder);
    }

    public void ensureWorldGenFiles(@NotNull NamespacedKey worldKey, @NotNull File worldDir, @NotNull ManagedWorldImpl mw) {
        fileService.ensureWorldGenFiles(worldKey, worldDir, mw, importService);
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
                    .autoLoad(source.isAutoLoad())
                    .fromTemplate(sourceKey);

            source.getConfiguredSpawn().ifPresent(s -> builder.spawn(s[0], s[1], s[2]));
            for (Map.Entry<org.bukkit.GameRule<?>, Object> entry : source.getGameRules().entrySet()) {
                WorldConfigService.applyGameRuleToBuilder(builder, (org.bukkit.GameRule<Object>) entry.getKey(), entry.getValue());
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
                            if (save) {
                                try {
                                    world.save();
                                } catch (Exception e) {
                                    plugin.getLogger().warning("Failed to save world before unload: " + world.getKey() + " - " + e.getMessage());
                                }
                            }
                            unloadFuture.complete(Bukkit.unloadWorld(world, save));
                        }, 1L);
                    } else {
                        scheduler.runTask(plugin, () -> {
                            if (save) {
                                try {
                                    world.save();
                                } catch (Exception e) {
                                    plugin.getLogger().warning("Failed to save world before unload: " + world.getKey() + " - " + e.getMessage());
                                }
                            }
                            unloadFuture.complete(Bukkit.unloadWorld(world, save));
                        });
                    }
                    return unloadFuture;
                }, runnable -> scheduler.runTask(plugin, runnable));
    }

    @Override
    public CompletableFuture<Boolean> deleteWorld(@NotNull NamespacedKey key, @Nullable NamespacedKey fallbackWorldKey) {
        if (key.equals(getMainLevel().getKey()) || key.equals(Bukkit.getWorlds().getFirst().getKey())) {
            plugin.getLogger().severe("Prevented attempt to delete the main server level.");
            return CompletableFuture.completedFuture(false);
        }

        exportService.claimExport(key);

        ManagedWorldImpl mw = registry.get(key);
        if (mw == null) {
            exportService.releaseExport(key);
            return CompletableFuture.completedFuture(false);
        }

        CompletableFuture<Boolean> result = new CompletableFuture<>();

        Bukkit.getScheduler().runTask(plugin, () -> {
            VoyageWorldDeleteEvent event = new VoyageWorldDeleteEvent(mw);
            Bukkit.getPluginManager().callEvent(event);
            if (event.isCancelled()) {
                exportService.releaseExport(key);
                result.complete(false);
                return;
            }

            unload(key, fallbackWorldKey, false).thenAccept(unloaded -> {
                if (!unloaded) {
                    plugin.getLogger().warning("Bukkit refused to unload world '" + key + "'. Deletion aborted.");
                    exportService.releaseExport(key);
                    result.complete(false);
                    return;
                }

                File folder = resolveWorldFolder(key);

                if (!folder.exists()) {
                    finalizeWorldRemoval(key);
                    exportService.releaseExport(key);
                    result.complete(true);
                    return;
                }

                deleteDirectory(folder).whenComplete((v, ex) -> {
                    exportService.releaseExport(key);
                    if (ex != null) {
                        plugin.getLogger().warning("Filesystem deletion failed for " + key + ": " + ex.getMessage());
                        result.complete(false);
                    } else {
                        finalizeWorldRemoval(key);
                        result.complete(true);
                    }
                });
            }).exceptionally(ex -> {
                exportService.releaseExport(key);
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

        Runnable fireEvent = () -> Bukkit.getPluginManager().callEvent(new VoyageWorldDeletedEvent(key));
        if (Bukkit.isPrimaryThread()) {
            fireEvent.run();
        } else {
            Bukkit.getScheduler().runTask(plugin, fireEvent);
        }
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

    @Override
    public CompletableFuture<Void> exportWorld(@NotNull NamespacedKey worldKey, @NotNull Path targetZipFile) {
        return exportService.exportWorld(worldKey, targetZipFile, null, null);
    }

    @Override
    public CompletableFuture<Void> exportWorld(@NotNull NamespacedKey worldKey, @NotNull Path targetZipFile,
                                               @Nullable UUID primaryPlayer, @Nullable String displayName) {
        return exportService.exportWorld(worldKey, targetZipFile, primaryPlayer, displayName, true);
    }

    @Override
    public CompletableFuture<Void> exportWorld(@NotNull NamespacedKey worldKey, @NotNull Path targetZipFile,
                                               @Nullable UUID primaryPlayer, @Nullable String displayName,
                                               boolean includeRootFolder) {
        return exportService.exportWorld(worldKey, targetZipFile, primaryPlayer, displayName, includeRootFolder);
    }

    @Override
    public CompletableFuture<ImportStrategy> importWorld(
            @NotNull Path sourceDir,
            @NotNull NamespacedKey targetKey,
            boolean autoLoad,
            boolean autoLink,
            @Nullable String dataProfileId,
            @Nullable DataLinkMergeStrategy mergeStrategy,
            boolean deleteSourceAfterImport,
            @Nullable World.Environment environment,
            @Nullable String generator,
            @Nullable String generatorSettings
    ) {
        return importService.importWorld(sourceDir, targetKey, autoLoad, autoLink, dataProfileId, mergeStrategy, deleteSourceAfterImport, environment, generator, generatorSettings);
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
        return importService.importWorld(sourceDir, targetKey, true, autoLink, dataProfileId, mergeStrategy, deleteSourceAfterImport, environment, generator, generatorSettings);
    }
}