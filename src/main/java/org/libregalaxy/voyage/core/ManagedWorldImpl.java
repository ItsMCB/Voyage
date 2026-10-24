package org.libregalaxy.voyage.core;

import org.bukkit.Bukkit;
import org.bukkit.Difficulty;
import org.bukkit.GameRule;
import org.bukkit.NamespacedKey;
import org.bukkit.World;
import org.bukkit.WorldType;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;
import org.libregalaxy.voyage.api.ManagedWorld;
import org.libregalaxy.voyage.api.VoyageAPI;
import org.libregalaxy.voyage.api.events.VoyageWorldLoadEvent;
import org.libregalaxy.voyage.api.events.VoyageWorldPreLoadEvent;
import org.libregalaxy.voyage.api.portal.PortalLink;
import org.libregalaxy.voyage.api.portal.PortalType;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;

public class ManagedWorldImpl implements ManagedWorld {

    private final NamespacedKey key;
    private final VoyageWorldManager manager;

    private final boolean temporary;
    private final World.Environment environment;
    private final long seed;
    private @Nullable String generator;
    private @NotNull WorldType worldType;
    private @Nullable String generatorSettings;
    private boolean generateStructures;
    private boolean autoLoad;

    private @NotNull Difficulty difficulty = Difficulty.NORMAL;
    private @Nullable int[] spawn;
    private @Nullable float[] spawnAngle;
    private final Map<GameRule<?>, Object> gameRules = new ConcurrentHashMap<>();

    private final Map<String, String> metadata = new ConcurrentHashMap<>();
    private final Map<PortalType, PortalLink> portalLinks = new ConcurrentHashMap<>();

    private @Nullable CompletableFuture<World> pendingLoad;

    public ManagedWorldImpl(@NotNull NamespacedKey key,
                            @NotNull VoyageWorldManager manager,
                            boolean temporary,
                            @NotNull World.Environment environment,
                            long seed,
                            @Nullable String generator,
                            boolean autoLoad,
                            @NotNull Map<PortalType, PortalLink> initialLinks) {
        this(key, manager, temporary, environment, seed, generator, null, WorldType.NORMAL,
                autoLoad, initialLinks, true);
    }

    public ManagedWorldImpl(@NotNull NamespacedKey key,
                            @NotNull VoyageWorldManager manager,
                            boolean temporary,
                            @NotNull World.Environment environment,
                            long seed,
                            @Nullable String generator,
                            @Nullable String generatorSettings,
                            @NotNull WorldType worldType,
                            boolean autoLoad,
                            @NotNull Map<PortalType, PortalLink> initialLinks) {
        this(key, manager, temporary, environment, seed, generator, generatorSettings, worldType,
                autoLoad, initialLinks, true);
    }

    public ManagedWorldImpl(@NotNull NamespacedKey key,
                            @NotNull VoyageWorldManager manager,
                            boolean temporary,
                            @NotNull World.Environment environment,
                            long seed,
                            @Nullable String generator,
                            @Nullable String generatorSettings,
                            @NotNull WorldType worldType,
                            boolean autoLoad,
                            @NotNull Map<PortalType, PortalLink> initialLinks,
                            boolean generateStructures) {
        boolean isVoid = VoyageAPI.isVoidWorld(generator, generatorSettings);

        this.key = key;
        this.manager = manager;
        this.temporary = temporary;
        this.environment = environment;
        this.seed = seed;
        this.generator = isVoid ? null : generator;
        this.generatorSettings = isVoid ? WorldBuilderImpl.VANILLA_VOID_SETTINGS : generatorSettings;
        this.worldType = isVoid ? WorldType.FLAT : worldType;
        this.autoLoad = autoLoad;
        this.generateStructures = !isVoid && generateStructures;
        this.portalLinks.putAll(initialLinks);
    }

    @Override public @NotNull NamespacedKey getKey()             { return key; }
    @Override public boolean isTemporary()                       { return temporary; }
    @Override public @NotNull World.Environment getEnvironment() { return environment; }
    @Override public long getSeed()                              { return seed; }
    @Override public @NotNull Optional<String> getGenerator()    { return Optional.ofNullable(generator); }
    @Override public @NotNull WorldType getWorldType()           { return worldType; }
    @Override public @NotNull Optional<String> getGeneratorSettings() { return Optional.ofNullable(generatorSettings); }
    @Override public boolean isAutoLoad()                        { return autoLoad; }
    @Override public @NotNull Difficulty getDifficulty()         { return difficulty; }
    @Override public boolean isGenerateStructures()              { return generateStructures; }

    public void setAutoLoad(boolean autoLoad) {
        this.autoLoad = autoLoad;
        manager.saveWorld(this);
    }

    public void clearLinks() { this.portalLinks.clear(); }

    @Override public boolean isLoaded() { return getBukkitWorld().isPresent(); }
    @Override public @NotNull Optional<World> getBukkitWorld() { return Optional.ofNullable(Bukkit.getWorld(key)); }

    @Override
    public @NotNull CompletableFuture<World> load() {
        World already = Bukkit.getWorld(key);
        if (already != null) return CompletableFuture.completedFuture(already);

        if (manager.isExporting(key)) {
            return CompletableFuture.failedFuture(
                    new IllegalStateException("World '" + key + "' is currently being exported or deleted. Try again in a moment."));
        }

        synchronized (this) {
            if (pendingLoad != null) return pendingLoad;
            pendingLoad = new CompletableFuture<>();
        }

        final CompletableFuture<World> future = pendingLoad;

        Runnable loadTask = () -> {
            try {
                VoyageWorldPreLoadEvent event = new VoyageWorldPreLoadEvent(this);
                Bukkit.getPluginManager().callEvent(event);

                if (event.isCancelled()) {
                    future.completeExceptionally(new IllegalStateException("World load cancelled by VoyageWorldPreLoadEvent."));
                    return;
                }

                World world = manager.loadBukkitWorld(this);
                if (world == null) {
                    future.completeExceptionally(new IllegalStateException(
                            "WorldCreator returned null for '" + key + "'. Check server logs."));
                    return;
                }

                Bukkit.getPluginManager().callEvent(new VoyageWorldLoadEvent(this));
                future.complete(world);
            } catch (Exception e) {
                future.completeExceptionally(e);
            } finally {
                synchronized (this) {
                    if (pendingLoad == future) {
                        pendingLoad = null;
                    }
                }
            }
        };

        if (Bukkit.isPrimaryThread()) {
            loadTask.run();
        } else {
            Bukkit.getScheduler().runTask(manager.getPlugin(), loadTask);
        }

        return future;
    }

    @Override
    public @NotNull CompletableFuture<Boolean> unload(boolean saveChunks) {
        return unload(saveChunks, null);
    }

    @Override
    public @NotNull CompletableFuture<Boolean> unload(boolean saveChunks, @Nullable World fallbackWorld) {
        NamespacedKey fallbackKey = fallbackWorld != null ? fallbackWorld.getKey() : null;
        return manager.unload(key, fallbackKey, saveChunks);
    }

    @Override
    public void setMetadata(@NotNull String key, @Nullable String value) {
        if (value == null) {
            metadata.remove(key);
        } else {
            metadata.put(key, value);
        }
        manager.saveWorld(this);
    }

    @Override
    public void removeMetadata(@NotNull String key) {
        metadata.remove(key);
        manager.saveWorld(this);
    }

    @Override
    public @NotNull Optional<String> getMetadata(@NotNull String key) {
        return Optional.ofNullable(metadata.get(key));
    }

    @Override
    public @NotNull Map<String, String> getAllMetadata() {
        return Collections.unmodifiableMap(new LinkedHashMap<>(metadata));
    }

    void setMetadataInternal(@NotNull Map<String, String> metadata) {
        this.metadata.clear();
        this.metadata.putAll(metadata);
    }

    @Override
    public @NotNull Optional<PortalLink> getPortalLink(@NotNull PortalType type) {
        return Optional.ofNullable(portalLinks.get(type));
    }

    @Override
    public void linkPortal(@NotNull PortalType type, @NotNull PortalLink link) {
        portalLinks.put(type, link);
        manager.saveWorld(this);
    }

    @Override
    public void unlinkPortal(@NotNull PortalType type) {
        portalLinks.remove(type);
        manager.saveWorld(this);
    }

    @Override
    public void setDifficulty(@NotNull Difficulty difficulty) {
        this.difficulty = difficulty;
        getBukkitWorld().ifPresent(world -> runOnMain(() -> {
            if (world.getDifficulty() != difficulty) {
                world.setDifficulty(difficulty);
            }
        }));
        manager.saveWorld(this);
    }

    @Override
    public void setSpawn(int x, int y, int z, float yaw, float pitch) {
        this.spawn = new int[]{x, y, z};
        this.spawnAngle = new float[]{yaw, pitch};
        getBukkitWorld().ifPresent(world -> runOnMain(() -> world.setSpawnLocation(x, y, z, yaw)));
        manager.saveWorld(this);
    }

    @Override
    public <T> void setGameRule(@NotNull GameRule<T> rule, @Nullable T value) {
        if (value == null) {
            gameRules.remove(rule);
        } else {
            gameRules.put(rule, value);
            getBukkitWorld().ifPresent(world -> runOnMain(() -> {
                try {
                    if (!Objects.equals(world.getGameRuleValue(rule), value)) {
                        world.setGameRule(rule, value);
                    }
                } catch (Exception ignored) {}
            }));
        }
        manager.saveWorld(this);
    }

    @SuppressWarnings("unchecked")
    @Override
    public <T> T getGameRule(@NotNull GameRule<T> rule) {
        Object configured = gameRules.get(rule);
        if (configured != null) return (T) configured;
        return getBukkitWorld().map(w -> {
            try { return w.getGameRuleValue(rule); }
            catch (Exception e) { return null; }
        }).orElse(null);
    }

    @Override
    public @NotNull Map<GameRule<?>, Object> getGameRules() {
        return Collections.unmodifiableMap(new LinkedHashMap<>(gameRules));
    }

    @Override
    public @NotNull Optional<int[]> getConfiguredSpawn() {
        return Optional.ofNullable(spawn).map(int[]::clone);
    }

    @Override
    public @NotNull Optional<float[]> getConfiguredSpawnAngles() {
        return Optional.ofNullable(spawnAngle).map(float[]::clone);
    }

    public void setDifficultyInternal(@NotNull Difficulty difficulty) {
        this.difficulty = difficulty;
    }

    void setSpawnInternal(@Nullable int[] spawn) {
        this.spawn = spawn != null ? spawn.clone() : null;
    }

    void setSpawnAngleInternal(@Nullable float[] angle) {
        this.spawnAngle = angle != null ? angle.clone() : null;
    }

    void setGameRulesInternal(@NotNull Map<GameRule<?>, Object> rules) {
        this.gameRules.clear();
        this.gameRules.putAll(rules);
    }

    void clearGameRulesInternal() {
        this.gameRules.clear();
    }

    @Nullable int[] getSpawnOverride() {
        return spawn != null ? spawn.clone() : null;
    }

    @Nullable float[] getSpawnAngleOverride() {
        return spawnAngle != null ? spawnAngle.clone() : null;
    }

    @SuppressWarnings("unchecked")
    void applyGameRulesTo(@NotNull World world) {
        for (Map.Entry<GameRule<?>, Object> entry : gameRules.entrySet()) {
            try {
                GameRule<Object> rule = (GameRule<Object>) entry.getKey();
                world.setGameRule(rule, entry.getValue());
            } catch (Exception ignored) {}
        }
    }

    private void runOnMain(@NotNull Runnable runnable) {
        if (Bukkit.isPrimaryThread()) {
            runnable.run();
        } else {
            Bukkit.getScheduler().runTask(manager.getPlugin(), runnable);
        }
    }

    public void setGameRuleInternal(@NotNull GameRule<?> rule, @Nullable Object value) {
        if (value != null) {
            this.gameRules.put(rule, value);
        }
    }

    @Override
    public void setWorldType(@NotNull WorldType worldType) {
        this.worldType = worldType;
        manager.saveWorld(this);
    }

    @Override
    public void setGeneratorSettings(@Nullable String generatorSettings) {
        if (VoyageAPI.isVoidSettings(generatorSettings)) {
            this.worldType = WorldType.FLAT;
            this.generatorSettings = WorldBuilderImpl.VANILLA_VOID_SETTINGS;
            this.generateStructures = false;
            this.generator = null;
        } else {
            this.generatorSettings = generatorSettings;
        }
        manager.saveWorld(this);
    }

    @Override
    public void setGenerator(@Nullable String generator) {
        if (WorldBuilderImpl.isVoidGenerator(generator)) {
            this.worldType = WorldType.FLAT;
            this.generatorSettings = WorldBuilderImpl.VANILLA_VOID_SETTINGS;
            this.generateStructures = false;
            this.generator = null;
        } else {
            this.generator = generator;
        }
        manager.saveWorld(this);
    }
}