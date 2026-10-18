package org.libregalaxy.voyage.api;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.NamespacedKey;
import org.bukkit.World;
import org.bukkit.block.Block;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;
import org.libregalaxy.voyage.api.builder.WorldBuilder;

import java.nio.file.Path;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.CompletableFuture;

public interface VoyageAPI {

    /**
     * Canonical vanilla flat-world NBT settings producing 100% void chunk generation.
     */
    String VANILLA_VOID_SETTINGS =
            "{\"layers\":[{\"block\":\"minecraft:air\",\"height\":1}],\"biome\":\"minecraft:the_void\",\"features\":false,\"lakes\":false}";

    /**
     * Checks if a generator string represents a void generator (e.g., "void", "voyage:void", "the_void").
     */
    static boolean isVoidGenerator(@Nullable String generator) {
        if (generator == null || generator.isBlank()) return false;
        String norm = generator.trim().toLowerCase(Locale.ROOT);
        return norm.equals("void")
                || norm.equals("voyage:void")
                || norm.equals("the_void")
                || norm.equals("minecraft:the_void");
    }

    /**
     * Checks if generator settings JSON (or legacy flat string) specifies a pure void world
     * (i.e. uses the_void biome AND has no non-air block layers).
     * Superflat worlds that use minecraft:the_void biome alongside actual solid/fluid block
     * layers return false so their layers and builds are preserved.
     */
    static boolean isVoidSettings(@Nullable String generatorSettings) {
        if (generatorSettings == null || !generatorSettings.contains("the_void")) {
            return false;
        }
        String trimmed = generatorSettings.trim();
        if (trimmed.startsWith("{")) {
            try {
                JsonObject obj = JsonParser.parseString(trimmed).getAsJsonObject();
                if (!obj.has("layers") || !obj.get("layers").isJsonArray()) {
                    return true;
                }
                JsonArray layers = obj.getAsJsonArray("layers");
                if (layers.isEmpty()) {
                    return true;
                }
                for (JsonElement elem : layers) {
                    if (!elem.isJsonObject()) continue;
                    JsonObject layer = elem.getAsJsonObject();
                    int height = layer.has("height") ? layer.get("height").getAsInt() : 1;
                    if (height <= 0) continue;
                    String block = layer.has("block") ? layer.get("block").getAsString().trim().toLowerCase(Locale.ROOT) : "minecraft:air";
                    if (!block.equals("minecraft:air") && !block.equals("air")
                            && !block.equals("minecraft:cave_air") && !block.equals("cave_air")
                            && !block.equals("minecraft:void_air") && !block.equals("void_air")) {
                        return false;
                    }
                }
                return true;
            } catch (Exception ignored) {
                return trimmed.equals(VANILLA_VOID_SETTINGS);
            }
        }
        // Legacy flat string format: e.g., "minecraft:air;minecraft:the_void"
        String[] parts = trimmed.split(";");
        String layersPart = parts.length >= 3 ? parts[1] : parts[0];
        for (String layerStr : layersPart.split(",")) {
            String clean = layerStr.trim().toLowerCase(Locale.ROOT);
            if (clean.isEmpty()) continue;
            if (clean.contains("*")) {
                String[] cb = clean.split("\\*", 2);
                try {
                    if (Integer.parseInt(cb[0].trim()) <= 0) continue;
                } catch (NumberFormatException ignored) {}
                clean = cb[1].trim();
            }
            if (!clean.equals("minecraft:air") && !clean.equals("air")
                    && !clean.equals("minecraft:cave_air") && !clean.equals("cave_air")
                    && !clean.equals("minecraft:void_air") && !clean.equals("void_air")) {
                return false;
            }
        }
        return true;
    }

    /**
     * Checks if a world configuration represents a void world based on generator or generator settings.
     */
    static boolean isVoidWorld(@Nullable String generator, @Nullable String generatorSettings) {
        return isVoidGenerator(generator) || isVoidSettings(generatorSettings);
    }

    /**
     * Checks if a world configuration represents a void world based on template ID, generator, or generator settings.
     */
    static boolean isVoidWorld(@Nullable String templateId, @Nullable String generator, @Nullable String generatorSettings) {
        if (templateId != null && templateId.equalsIgnoreCase("void")) return true;
        return isVoidWorld(generator, generatorSettings);
    }

    /**
     * Resolves a safe location by checking for solid block obstruction and mid-air drops.
     * If the location is inside solid blocks, scans upward up to 64 blocks for safe clearance.
     * If in mid-air, searches downward up to 32 blocks for solid ground.
     * If in pure void, ensures a bedrock block beneath the location if called on the primary thread.
     */
    static @NotNull Location getSafeLocation(@NotNull Location location) {
        World world = location.getWorld();
        if (world == null) return location;

        Location safe = location.clone();
        int bx = safe.getBlockX();
        int by = safe.getBlockY();
        int bz = safe.getBlockZ();

        // If inside solid blocks, scan upward for safe clearance
        if (world.getBlockAt(bx, by, bz).getType().isSolid() || world.getBlockAt(bx, by + 1, bz).getType().isSolid()) {
            for (int dy = 1; dy <= 64; dy++) {
                int testY = by + dy;
                if (testY < world.getMaxHeight() - 1
                        && !world.getBlockAt(bx, testY, bz).getType().isSolid()
                        && !world.getBlockAt(bx, testY + 1, bz).getType().isSolid()) {
                    safe.setY(testY);
                    return safe;
                }
            }
        } else {
            // If in mid-air, search downwards up to 32 blocks for solid ground
            int minCheckY = Math.max(world.getMinHeight(), by - 32);
            boolean foundGround = false;
            for (int testY = by - 1; testY >= minCheckY; testY--) {
                if (world.getBlockAt(bx, testY, bz).getType().isSolid()) {
                    safe.setY(testY + 1);
                    foundGround = true;
                    break;
                }
            }
            // If in pure void, ensure a bedrock block beneath the player's feet
            if (!foundGround) {
                Block below = world.getBlockAt(safe.getBlockX(), safe.getBlockY() - 1, safe.getBlockZ());
                if (below.getType().isAir() && Bukkit.isPrimaryThread()) {
                    below.setType(Material.BEDROCK);
                }
            }
        }

        return safe;
    }

    /**
     * Resolves the safe spawn location for a world, centering on the block (+0.5, +0.5)
     * and ensuring safe clearance above solid ground.
     */
    static @NotNull Location getSafeSpawnLocation(@NotNull World world) {
        Location spawn = world.getSpawnLocation().clone();
        spawn.setX(spawn.getBlockX() + 0.5);
        spawn.setZ(spawn.getBlockZ() + 0.5);
        return getSafeLocation(spawn);
    }

    World getMainLevel();
    boolean worldExists(NamespacedKey key);
    Set<NamespacedKey> getAllWorlds();

    Optional<ManagedWorld> getManagedWorld(NamespacedKey key);
    List<ManagedWorld> getTrackedWorlds();
    List<ManagedWorld> getLoadedWorlds();

    List<String> getTrackedWorldNames();
    List<String> getLoadedTrackedWorldNames();
    List<String> getOfflineTrackedWorldNames();
    List<String> getLoadedWorldNames();

    // Powered by a real-time memory cache. Safe for Brigadier suggestions.
    List<String> getAllWorldNames();

    WorldBuilder builder(NamespacedKey key);

    CompletableFuture<Boolean> deleteWorld(NamespacedKey key, NamespacedKey fallbackWorld);
    CompletableFuture<Void> untrackWorld(NamespacedKey key);

    /**
     * Clones an existing managed world into a new target dimension.
     * Unloads the source world safely (if loaded) to ensure chunk consistency,
     * copies dimension files and configurations, duplicates gamerules, spawn,
     * metadata, and optionally clones player profile data before remounting.
     *
     * @param sourceKey        The NamespacedKey of the source dimension to clone.
     * @param targetKey        The NamespacedKey for the cloned dimension.
     * @param clonePlayerData  Whether to clone isolated player data profiles to the target dimension.
     * @param progress         Optional progress consumer for data cloning.
     * @return CompletableFuture resolving to the cloned ManagedWorld instance.
     */
    CompletableFuture<ManagedWorld> cloneWorld(@NotNull NamespacedKey sourceKey, @NotNull NamespacedKey targetKey, boolean clonePlayerData, @Nullable java.util.function.DoubleConsumer progress);

    default CompletableFuture<ManagedWorld> cloneWorld(@NotNull NamespacedKey sourceKey, @NotNull NamespacedKey targetKey, boolean clonePlayerData) {
        return cloneWorld(sourceKey, targetKey, clonePlayerData, null);
    }

    default CompletableFuture<ManagedWorld> cloneWorld(@NotNull NamespacedKey sourceKey, @NotNull NamespacedKey targetKey) {
        return cloneWorld(sourceKey, targetKey, true, null);
    }

    /**
     * Clones the isolated player data profile from a source world to a target world.
     *
     * @param sourceKey The source dimension key.
     * @param targetKey The target dimension key.
     * @param progress  Optional progress callback (0.0 to 1.0).
     * @return CompletableFuture tracking the data cloning operation.
     */
    CompletableFuture<Void> cloneWorldData(@NotNull NamespacedKey sourceKey, @NotNull NamespacedKey targetKey, @Nullable java.util.function.DoubleConsumer progress);

    default CompletableFuture<Void> cloneWorldData(@NotNull NamespacedKey sourceKey, @NotNull NamespacedKey targetKey) {
        return cloneWorldData(sourceKey, targetKey, null);
    }

    /**
     * Imports a world from a legacy or vanilla directory structure into the Paper 26.x format.
     */
    CompletableFuture<ImportStrategy> importWorld(
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
    );

    default CompletableFuture<ImportStrategy> importWorld(
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
        return importWorld(sourceDir, targetKey, true, autoLink, dataProfileId, mergeStrategy, deleteSourceAfterImport, environment, generator, generatorSettings);
    }

    default CompletableFuture<ImportStrategy> importWorld(
            @NotNull Path sourceDir,
            @NotNull NamespacedKey targetKey,
            boolean autoLink,
            @Nullable String dataProfileId,
            @Nullable DataLinkMergeStrategy mergeStrategy,
            boolean deleteSourceAfterImport,
            @Nullable World.Environment environment
    ) {
        return importWorld(sourceDir, targetKey, true, autoLink, dataProfileId, mergeStrategy, deleteSourceAfterImport, environment, null, null);
    }

    /**
     * Exports a world to a zipped archive.
     */
    CompletableFuture<Void> exportWorld(@NotNull NamespacedKey worldKey, @NotNull Path targetZipFile);

    /**
     * Exports a world to a zipped archive, embedding the given player's
     * Voyage state as level.dat's Data.Player — the data the vanilla
     * singleplayer host receives when opening the save (position, inventory,
     * ender chest, effects, game mode).
     */
    default CompletableFuture<Void> exportWorld(@NotNull NamespacedKey worldKey, @NotNull Path targetZipFile,
                                                @Nullable java.util.UUID primaryPlayer) {
        return exportWorld(worldKey, targetZipFile, primaryPlayer, null);
    }

    /**
     * Exports a world to a zipped archive with full player data and a
     * human-readable world name (shown in the vanilla world list; the folder
     * inside the archive stays unique per world).
     */
    default CompletableFuture<Void> exportWorld(@NotNull NamespacedKey worldKey, @NotNull Path targetZipFile,
                                                @Nullable java.util.UUID primaryPlayer,
                                                @Nullable String displayName) {
        // Legacy implementations without the extended export fall back to the
        // plain archive (no embedded player data, default naming).
        return exportWorld(worldKey, targetZipFile);
    }

    enum ImportStrategy {
        PAPER_26_X("Paper 26.x Format"),
        VANILLA_26_X("Vanilla 26.x Format"),
        PAPER_1_21_11("Paper 1.21.11 Format"),
        VANILLA_1_21_11("Vanilla 1.21.11 Format"),
        UNKNOWN("Unknown Format");

        private final String displayName;

        ImportStrategy(String displayName) {
            this.displayName = displayName;
        }

        public String getDisplayName() {
            return displayName;
        }
    }

    /**
     * Gets the profile ID currently responsible for storing player data in the given world.
     */
    String getWorldDataProfile(NamespacedKey worldKey);

    enum DataLinkMergeStrategy {
        SOURCE_PRIORITY,
        DESTINATION_PRIORITY
    }

    record DataLinkPreview(int newToTarget, int conflicts, int targetOnly, DataLinkMergeStrategy strategy) {}

    DataLinkPreview previewWorldDataProfile(NamespacedKey worldKey, String profileId, DataLinkMergeStrategy strategy);

    /**
     * Dynamically sets the data profile ID for a world. Worlds sharing the same ID share player data.
     * Pass null or "none" as the profileId to isolate the world into its own unique data profile.
     */
    CompletableFuture<Void> setWorldDataProfile(NamespacedKey worldKey, String profileId, DataLinkMergeStrategy strategy, java.util.function.DoubleConsumer progress);

    default CompletableFuture<Void> setWorldDataProfile(NamespacedKey worldKey, String profileId, DataLinkMergeStrategy strategy) {
        return setWorldDataProfile(worldKey, profileId, strategy, null);
    }

    /**
     * Isolates the world into its own unique data profile.
     * @param cloneData If true, copies the group's current data into the new isolated profile.
     */
    CompletableFuture<Void> breakWorldDataLink(NamespacedKey worldKey, boolean cloneData, java.util.function.DoubleConsumer progress);

    default CompletableFuture<Void> breakWorldDataLink(NamespacedKey worldKey, boolean cloneData) {
        return breakWorldDataLink(worldKey, cloneData, null);
    }

    record OfflineLocation(NamespacedKey worldKey, double x, double y, double z, float yaw, float pitch) {}

    /**
     * Retrieves the last physical location where the player logged off within the given world's profile group.
     */
    Optional<OfflineLocation> getPlayerLogOffLocation(NamespacedKey worldKey, java.util.UUID playerUuid);

    /**
     * Resolves the appropriate destination location when a player enters a world.
     * If preferPreviousLocation is true, restores to the player's last known location
     * via getPlayerLogOffLocation (ensuring safe clearance); otherwise falls back to
     * the safe centered spawn location.
     *
     * @param targetWorld            The Bukkit world to enter.
     * @param playerUuid             The UUID of the player.
     * @param preferPreviousLocation If true, restores previous location if available.
     * @return The resolved safe Location in targetWorld.
     */
    default org.bukkit.Location getPlayerEntryLocation(@NotNull World targetWorld, @NotNull java.util.UUID playerUuid, boolean preferPreviousLocation) {
        if (preferPreviousLocation) {
            Optional<OfflineLocation> offLoc = getPlayerLogOffLocation(targetWorld.getKey(), playerUuid);
            if (offLoc.isPresent() && offLoc.get().worldKey().equals(targetWorld.getKey())) {
                var off = offLoc.get();
                return getSafeLocation(new org.bukkit.Location(targetWorld, off.x(), off.y(), off.z(), off.yaw(), off.pitch()));
            }
        }
        return getSafeSpawnLocation(targetWorld);
    }

    default org.bukkit.Location getPlayerEntryLocation(@NotNull World targetWorld, @NotNull java.util.UUID playerUuid) {
        return getPlayerEntryLocation(targetWorld, playerUuid, true);
    }
}