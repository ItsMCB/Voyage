package org.libregalaxy.voyage.api;

import org.bukkit.Difficulty;
import org.bukkit.GameRule;
import org.bukkit.Location;
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

    // Metadata tagging for other plugins
    void setMetadata(@NotNull String key, @Nullable String value);
    void removeMetadata(@NotNull String key);
    Optional<String> getMetadata(@NotNull String key);
    Map<String, String> getAllMetadata();

    // Traffic Controller methods
    Optional<PortalLink> getPortalLink(PortalType type);
    void linkPortal(PortalType type, PortalLink link);
    void unlinkPortal(PortalType type);

    // --- Runtime mutators ---

    void setDifficulty(Difficulty difficulty);

    /**
     * Sets the world's spawn location with block coordinates.
     */
    default void setSpawn(int x, int y, int z) {
        setSpawn(x, y, z, 0.0f, 0.0f);
    }

    /**
     * Sets the world's spawn location with block coordinates, yaw, and pitch.
     */
    void setSpawn(int x, int y, int z, float yaw, float pitch);

    default void setSpawn(@NotNull Location location) {
        setSpawn(location.getBlockX(), location.getBlockY(), location.getBlockZ(), location.getYaw(), location.getPitch());
    }

    <T> void setGameRule(GameRule<T> rule, T value);
    <T> T getGameRule(GameRule<T> rule);
    Map<GameRule<?>, Object> getGameRules();

    Optional<int[]> getConfiguredSpawn();
    default Optional<float[]> getConfiguredSpawnAngles() { return Optional.empty(); }

    void setWorldType(@NotNull WorldType worldType);
    void setGeneratorSettings(@Nullable String generatorSettings);
    void setGenerator(@Nullable String generator);
}