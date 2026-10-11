package org.libregalaxy.voyage.core;

import org.bukkit.Bukkit;
import org.bukkit.Difficulty;
import org.bukkit.GameRule;
import org.bukkit.NamespacedKey;
import org.bukkit.World;
import org.bukkit.WorldType;
import org.bukkit.configuration.ConfigurationSection;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;
import org.libregalaxy.voyage.api.ManagedWorld;
import org.libregalaxy.voyage.api.VoyageAPI;
import org.libregalaxy.voyage.api.builder.WorldBuilder;
import org.libregalaxy.voyage.api.portal.PortalLink;
import org.libregalaxy.voyage.api.portal.PortalType;

import java.io.File;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.concurrent.ThreadLocalRandom;
import java.util.concurrent.TimeUnit;

public class WorldBuilderImpl implements WorldBuilder {

    public static final String VANILLA_VOID_SETTINGS = VoyageAPI.VANILLA_VOID_SETTINGS;

    public static boolean isVoidGenerator(@Nullable String name) {
        return VoyageAPI.isVoidGenerator(name);
    }

    private final NamespacedKey key;
    private final VoyageWorldManager manager;

    private World.Environment environment = World.Environment.NORMAL;
    private long seed = ThreadLocalRandom.current().nextLong();
    private @Nullable String generatorName = null;
    private @NotNull WorldType worldType = WorldType.NORMAL;
    private @Nullable String generatorSettings = null;
    private boolean autoLoad = true;
    private boolean temporary = false;
    private boolean importExisting = false;
    private @Nullable NamespacedKey templateKey = null;
    private @Nullable String presetName = null;

    private @Nullable Difficulty difficulty = null;
    private boolean generateStructures = true;
    private @Nullable int[] spawn = null;
    private final Map<GameRule<?>, Object> gameRules = new LinkedHashMap<>();
    private final Map<String, String> metadata = new LinkedHashMap<>();

    private boolean bypassValidation = false;

    private final Map<PortalType, PortalLink> initialLinks = new HashMap<>();

    public WorldBuilderImpl(@NotNull NamespacedKey key, @NotNull VoyageWorldManager manager) {
        this.key     = key;
        this.manager = manager;
    }

    @Override
    public WorldBuilder environment(@Nullable World.Environment environment) {
        this.environment = environment != null ? environment : World.Environment.NORMAL;
        return this;
    }

    @Override public WorldBuilder seed(long seed) { this.seed = seed; return this; }

    @Override
    public WorldBuilder seed(@Nullable String seed) {
        if (seed == null || seed.isBlank()) return this;
        this.seed = WorldBuilder.parseSeed(seed);
        return this;
    }

    @Override
    public WorldBuilder generator(@Nullable String name) {
        if (VoyageAPI.isVoidGenerator(name)) {
            this.worldType = WorldType.FLAT;
            this.generatorSettings = VoyageAPI.VANILLA_VOID_SETTINGS;
            this.generateStructures = false;
            this.generatorName = null;
            return this;
        }
        this.generatorName = name;
        return this;
    }

    @Override public WorldBuilder type(@Nullable WorldType worldType) { this.worldType = worldType != null ? worldType : WorldType.NORMAL; return this; }
    @Override public WorldBuilder generatorSettings(@Nullable String settings) { this.generatorSettings = settings; return this; }
    @Override public WorldBuilder autoLoad(boolean autoLoad)         { this.autoLoad = autoLoad; return this; }
    @Override public WorldBuilder importExisting(boolean v)          { this.importExisting = v; return this; }
    @Override public WorldBuilder asTemporaryInstance()              { this.temporary = true; return this; }
    @Override public WorldBuilder fromTemplate(@NotNull NamespacedKey k) { this.templateKey = k; return this; }
    @Override public WorldBuilder linkPortal(@NotNull PortalType t, @NotNull PortalLink l) { initialLinks.put(t, l); return this; }

    @Override public WorldBuilder difficulty(@Nullable Difficulty difficulty) { this.difficulty = difficulty; return this; }
    @Override public WorldBuilder generateStructures(boolean generate)        { this.generateStructures = generate; return this; }
    @Override public WorldBuilder spawn(int x, int y, int z)                  { this.spawn = new int[]{x, y, z}; return this; }

    @Override
    public WorldBuilder preset(String presetName) {
        this.presetName = presetName;
        if (VoyageAPI.isVoidGenerator(presetName)) {
            environment(World.Environment.NORMAL);
            type(WorldType.FLAT);
            generatorSettings(VoyageAPI.VANILLA_VOID_SETTINGS);
            generateStructures(false);
            generator(null);
        }
        return this;
    }

    @Override
    public <T> WorldBuilder gameRule(@NotNull GameRule<T> rule, @Nullable T value) {
        if (value == null) {
            gameRules.remove(rule);
        } else {
            gameRules.put(rule, value);
        }
        return this;
    }

    @Override
    public WorldBuilder metadata(@NotNull String key, @Nullable String value) {
        if (value == null) {
            metadata.remove(key);
        } else {
            metadata.put(key, value);
        }
        return this;
    }

    @Override
    public WorldBuilder metadata(@NotNull Map<String, String> metadata) {
        this.metadata.putAll(metadata);
        return this;
    }

    void setBypassValidation(boolean bypass) { this.bypassValidation = bypass; }

    @Nullable Difficulty getDifficultyConfig()   { return difficulty; }
    boolean getGenerateStructuresConfig()         { return generateStructures; }
    @Nullable int[] getSpawnConfig()              { return spawn; }
    @NotNull Map<GameRule<?>, Object> getGameRulesConfig() { return gameRules; }

    private static class WorldAlreadyManagedException extends RuntimeException {}

    @Override
    public CompletableFuture<ManagedWorld> create() {
        if (!manager.claimBuild(key)) {
            return CompletableFuture.failedFuture(new IllegalStateException("World is currently being built: " + key));
        }

        if (!bypassValidation) {
            File resolved = manager.resolveWorldFolder(key);
            if (importExisting) {
                if (!resolved.exists() && Bukkit.getWorld(key) == null) {
                    manager.releaseBuild(key);
                    return CompletableFuture.failedFuture(new IllegalStateException(
                            "World directory does not exist for import: " + key));
                }
            } else if (Bukkit.getWorld(key) != null || resolved.exists()) {
                manager.releaseBuild(key);
                return CompletableFuture.failedFuture(new IllegalStateException(
                        "World directory or Bukkit world already exists for '" + key
                                + "'. Use importExisting(true) to track it."));
            }
        }

        if (presetName != null) {
            if (VoyageAPI.isVoidGenerator(presetName)) {
                environment(World.Environment.NORMAL);
                type(WorldType.FLAT);
                generatorSettings(VoyageAPI.VANILLA_VOID_SETTINGS);
                generateStructures(false);
                generator(null);
            } else {
                try {
                    ConfigurationSection presets = manager.getPlugin().getConfig().getConfigurationSection("presets");
                    if (presets != null) {
                        ConfigurationSection preset = presets.getConfigurationSection(presetName);
                        if (preset != null) {
                            if (preset.isString("environment")) environment(World.Environment.valueOf(preset.getString("environment")));
                            if (preset.isString("generator")) generator(preset.getString("generator"));
                            if (preset.isString("worldType")) type(WorldType.valueOf(preset.getString("worldType")));
                            if (preset.isString("generatorSettings")) generatorSettings(preset.getString("generatorSettings"));
                            if (preset.isBoolean("generateStructures")) generateStructures(preset.getBoolean("generateStructures"));
                        } else {
                            manager.getPlugin().getLogger().warning("Preset '" + presetName + "' not found in config.yml. Ignoring.");
                        }
                    }
                } catch (Exception e) {
                    manager.releaseBuild(key);
                    return CompletableFuture.failedFuture(e);
                }
            }
        }

        // Canonical normalization for void worlds: persist as vanilla flat void with structures disabled
        if (VoyageAPI.isVoidWorld(generatorName, generatorSettings)) {
            this.worldType = WorldType.FLAT;
            this.generatorSettings = VoyageAPI.VANILLA_VOID_SETTINGS;
            this.generateStructures = false;
            this.generatorName = null;
        }

        CompletableFuture<ManagedWorld> future = CompletableFuture.supplyAsync(() -> {
                    File dst = manager.resolveWorldFolder(key);

                    if (!bypassValidation && !importExisting) {
                        dst.mkdirs();
                    }

                    if (templateKey != null) {
                        File src = manager.resolveWorldFolder(templateKey);
                        if (src.exists()) {
                            try {
                                manager.copyDirectory(src, dst);
                            } catch (Exception e) {
                                manager.getPlugin().getLogger().severe(
                                        "Failed to copy template '" + templateKey + "' for '" + key + "': " + e.getMessage());
                                throw new CompletionException("Template copy failed", e);
                            }
                        } else {
                            throw new CompletionException(new IllegalStateException("Template world '" + templateKey + "' does not exist on disk. Operation aborted."));
                        }
                    }

                    if (temporary) {
                        try {
                            new File(dst, ".voyage-temp").createNewFile();
                        } catch (Exception ignored) {}
                    }

                    return (Void) null;
                }, r -> Bukkit.getScheduler().runTaskAsynchronously(manager.getPlugin(), r))

                .thenComposeAsync(v -> {
                    ManagedWorldImpl mw = new ManagedWorldImpl(
                            key, manager, temporary, environment, seed, generatorName,
                            generatorSettings, worldType, autoLoad, Map.copyOf(initialLinks),
                            generateStructures);

                    mw.setDifficultyInternal(difficulty != null ? difficulty : Difficulty.NORMAL);
                    if (spawn != null) mw.setSpawnInternal(spawn.clone());
                    mw.setGameRulesInternal(gameRules);
                    mw.setMetadataInternal(metadata);

                    if (!manager.registerNewWorld(mw)) {
                        throw new WorldAlreadyManagedException();
                    }

                    if (autoLoad || temporary) {
                        return mw.load().handle((w, ex) -> {
                            if (ex != null) {
                                manager.unregisterWorld(key);
                                Throwable cause = ex instanceof CompletionException ? ex.getCause() : ex;
                                manager.getPlugin().getLogger().log(java.util.logging.Level.SEVERE,
                                        "Failed to load world '" + key + "'.", cause);
                                throw new CompletionException(cause);
                            }
                            return (ManagedWorld) mw;
                        });
                    }
                    return CompletableFuture.completedFuture((ManagedWorld) mw);
                }, r -> Bukkit.getScheduler().runTask(manager.getPlugin(), r))

                .exceptionally(ex -> {
                    Throwable cause = ex instanceof CompletionException ? ex.getCause() : ex;

                    if (cause instanceof WorldAlreadyManagedException) {
                        throw new CompletionException(new IllegalStateException("World already managed (concurrent race): " + key));
                    }

                    if (!importExisting) {
                        File dst = manager.resolveWorldFolder(key);
                        if (dst.exists()) {
                            Bukkit.getScheduler().runTaskAsynchronously(manager.getPlugin(), () -> {
                                try {
                                    manager.deleteDirectory(dst)
                                            .orTimeout(30, TimeUnit.SECONDS)
                                            .exceptionally(timeoutEx -> {
                                                manager.getPlugin().getLogger().warning("Timed out cleaning up failed world: " + key);
                                                return null;
                                            });
                                } catch (Exception ignored) {}
                            });
                        }
                    }
                    if (ex instanceof CompletionException) {
                        throw (CompletionException) ex;
                    }
                    throw new CompletionException(ex);
                });

        future.whenComplete((w, ex) -> manager.releaseBuild(key));
        return future;
    }
}