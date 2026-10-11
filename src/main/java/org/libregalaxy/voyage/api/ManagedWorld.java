package org.libregalaxy.voyage.api;

import org.bukkit.Difficulty;
import org.bukkit.GameRule;
import org.bukkit.NamespacedKey;
import org.bukkit.World;
import org.bukkit.WorldType;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;
import org.libregalaxy.voyage.api.portal.PortalLink;
import org.libregalaxy.voyage.api.portal.PortalType;

import java.util.Map;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;

public interface ManagedWorld {
    NamespacedKey getKey();
    boolean isLoaded();
    boolean isTemporary();

    // Saved to config
    World.Environment getEnvironment();
    long getSeed();
    Optional<String> getGenerator();
    WorldType getWorldType();
    Optional<String> getGeneratorSettings();
    boolean isAutoLoad();

    /**
     * Returns the difficulty configured for this world. If no difficulty was
     * explicitly set, returns {@link Difficulty#NORMAL}.
     */
    Difficulty getDifficulty();

    /**
     * Returns whether structure generation is enabled for this world.
     */
    boolean isGenerateStructures();

    Optional<World> getBukkitWorld();

    CompletableFuture<World> load();
    CompletableFuture<Boolean> unload(boolean saveChunks);
    CompletableFuture<Boolean> unload(boolean saveChunks, World fallbackWorld);

    // Metadata tagging for other plugins (persisted to worlds.yml)
    void setMetadata(@NotNull String key, @Nullable String value);
    void removeMetadata(@NotNull String key);
    Optional<String> getMetadata(@NotNull String key);
    Map<String, String> getAllMetadata();

    // Traffic Controller methods
    Optional<PortalLink> getPortalLink(PortalType type);
    void linkPortal(PortalType type, PortalLink link);
    void unlinkPortal(PortalType type);

    // --- Runtime mutators (persisted to config) ---

    /**
     * Sets the world's difficulty. If the world is currently loaded, the
     * change is applied immediately (scheduled on the main thread if called
     * asynchronously). The value is persisted to worlds.yml.
     */
    void setDifficulty(Difficulty difficulty);

    /**
     * Sets the world's spawn location. If the world is currently loaded, the
     * change is applied immediately. The value is persisted to worlds.yml.
     */
    void setSpawn(int x, int y, int z);

    /**
     * Sets a gamerule. If the world is currently loaded, the change is applied
     * immediately. The value is persisted to worlds.yml.
     */
    <T> void setGameRule(GameRule<T> rule, T value);

    /**
     * Gets the configured value for a gamerule, or the world's current value
     * if loaded, or {@code null} if not set and not loaded.
     */
    <T> T getGameRule(GameRule<T> rule);

    /**
     * Returns an unmodifiable view of all explicitly configured gamerules.
     */
    Map<GameRule<?>, Object> getGameRules();

    /**
     * Returns the spawn coordinates explicitly configured via the API or
     * worlds.yml. Returns empty if no spawn override is set (the world will
     * use its own default spawn).
     */
    Optional<int[]> getConfiguredSpawn();

    void setWorldType(@NotNull WorldType worldType);
    void setGeneratorSettings(@Nullable String generatorSettings);
    void setGenerator(@Nullable String generator);
}