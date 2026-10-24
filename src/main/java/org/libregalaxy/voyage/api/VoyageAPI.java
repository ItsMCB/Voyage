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

    String VANILLA_VOID_SETTINGS =
            "{\"layers\":[{\"block\":\"minecraft:air\",\"height\":1}],\"biome\":\"minecraft:the_void\",\"features\":false,\"lakes\":false}";

    static boolean isVoidGenerator(@Nullable String generator) {
        if (generator == null || generator.isBlank()) return false;
        String norm = generator.trim().toLowerCase(Locale.ROOT);
        return norm.equals("void")
                || norm.equals("voyage:void")
                || norm.equals("the_void")
                || norm.equals("minecraft:the_void");
    }

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

    static boolean isVoidWorld(@Nullable String generator, @Nullable String generatorSettings) {
        return isVoidGenerator(generator) || isVoidSettings(generatorSettings);
    }

    static boolean isVoidWorld(@Nullable String templateId, @Nullable String generator, @Nullable String generatorSettings) {
        if (templateId != null && templateId.equalsIgnoreCase("void")) return true;
        return isVoidWorld(generator, generatorSettings);
    }

    static @NotNull Location getSafeLocation(@NotNull Location location) {
        World world = location.getWorld();
        if (world == null) return location;

        Location safe = location.clone();
        int bx = safe.getBlockX();
        int by = safe.getBlockY();
        int bz = safe.getBlockZ();

        Block feetBlock = world.getBlockAt(bx, by, bz);
        Block headBlock = world.getBlockAt(bx, by + 1, bz);
        if (feetBlock == null || headBlock == null) return safe;

        Material feetMaterial = feetBlock.getType();
        Material headMaterial = headBlock.getType();
        if (feetMaterial == null || headMaterial == null) return safe;

        if (feetMaterial.isSolid() || headMaterial.isSolid()) {
            for (int dy = 1; dy <= 64; dy++) {
                int testY = by + dy;
                if (testY >= world.getMaxHeight() - 1) continue;
                Block testFeet = world.getBlockAt(bx, testY, bz);
                Block testHead = world.getBlockAt(bx, testY + 1, bz);
                if (testFeet == null || testHead == null) continue;
                Material testFeetMaterial = testFeet.getType();
                Material testHeadMaterial = testHead.getType();
                if (testFeetMaterial == null || testHeadMaterial == null) continue;
                if (!testFeetMaterial.isSolid() && !testHeadMaterial.isSolid()) {
                    safe.setY(testY);
                    return safe;
                }
            }
        } else {
            int minCheckY = Math.max(world.getMinHeight(), by - 32);
            boolean foundGround = false;
            for (int testY = by - 1; testY >= minCheckY; testY--) {
                Block test = world.getBlockAt(bx, testY, bz);
                if (test == null) continue;
                Material testMaterial = test.getType();
                if (testMaterial == null) continue;
                if (testMaterial.isSolid()) {
                    safe.setY(testY + 1);
                    foundGround = true;
                    break;
                }
            }
            if (!foundGround) {
                Block below = world.getBlockAt(safe.getBlockX(), safe.getBlockY() - 1, safe.getBlockZ());
                if (below != null) {
                    Material belowMaterial = below.getType();
                    if (belowMaterial != null && belowMaterial.isAir() && Bukkit.isPrimaryThread()) {
                        below.setType(Material.BEDROCK);
                    }
                }
            }
        }

        return safe;
    }

    static @NotNull Location getSafeSpawnLocation(@NotNull World world) {
        Location spawn = world.getSpawnLocation();
        if (spawn == null) {
            return new Location(world, 0.5, 64.0, 0.5);
        }
        spawn = spawn.clone();
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

    List<String> getAllWorldNames();

    WorldBuilder builder(NamespacedKey key);

    CompletableFuture<Boolean> deleteWorld(NamespacedKey key, NamespacedKey fallbackWorld);
    CompletableFuture<Void> untrackWorld(NamespacedKey key);

    CompletableFuture<ManagedWorld> cloneWorld(@NotNull NamespacedKey sourceKey, @NotNull NamespacedKey targetKey, boolean clonePlayerData, @Nullable java.util.function.DoubleConsumer progress);

    default CompletableFuture<ManagedWorld> cloneWorld(@NotNull NamespacedKey sourceKey, @NotNull NamespacedKey targetKey, boolean clonePlayerData) {
        return cloneWorld(sourceKey, targetKey, clonePlayerData, null);
    }

    default CompletableFuture<ManagedWorld> cloneWorld(@NotNull NamespacedKey sourceKey, @NotNull NamespacedKey targetKey) {
        return cloneWorld(sourceKey, targetKey, true, null);
    }

    CompletableFuture<Void> cloneWorldData(@NotNull NamespacedKey sourceKey, @NotNull NamespacedKey targetKey, @Nullable java.util.function.DoubleConsumer progress);

    default CompletableFuture<Void> cloneWorldData(@NotNull NamespacedKey sourceKey, @NotNull NamespacedKey targetKey) {
        return cloneWorldData(sourceKey, targetKey, null);
    }

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

    CompletableFuture<Void> exportWorld(@NotNull NamespacedKey worldKey, @NotNull Path targetZipFile);

    default CompletableFuture<Void> exportWorld(@NotNull NamespacedKey worldKey, @NotNull Path targetZipFile,
                                                @Nullable java.util.UUID primaryPlayer) {
        return exportWorld(worldKey, targetZipFile, primaryPlayer, null);
    }

    default CompletableFuture<Void> exportWorld(@NotNull NamespacedKey worldKey, @NotNull Path targetZipFile,
                                                @Nullable java.util.UUID primaryPlayer,
                                                @Nullable String displayName) {
        return exportWorld(worldKey, targetZipFile, primaryPlayer, displayName, true);
    }

    default CompletableFuture<Void> exportWorld(@NotNull NamespacedKey worldKey, @NotNull Path targetZipFile,
                                                @Nullable java.util.UUID primaryPlayer,
                                                @Nullable String displayName,
                                                boolean includeRootFolder) {
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

    String getWorldDataProfile(NamespacedKey worldKey);

    enum DataLinkMergeStrategy {
        SOURCE_PRIORITY,
        DESTINATION_PRIORITY
    }

    record DataLinkPreview(int newToTarget, int conflicts, int targetOnly, DataLinkMergeStrategy strategy) {}

    DataLinkPreview previewWorldDataProfile(NamespacedKey worldKey, String profileId, DataLinkMergeStrategy strategy);

    CompletableFuture<Void> setWorldDataProfile(NamespacedKey worldKey, String profileId, DataLinkMergeStrategy strategy, java.util.function.DoubleConsumer progress);

    default CompletableFuture<Void> setWorldDataProfile(NamespacedKey worldKey, String profileId, DataLinkMergeStrategy strategy) {
        return setWorldDataProfile(worldKey, profileId, strategy, null);
    }

    CompletableFuture<Void> breakWorldDataLink(NamespacedKey worldKey, boolean cloneData, java.util.function.DoubleConsumer progress);

    default CompletableFuture<Void> breakWorldDataLink(NamespacedKey worldKey, boolean cloneData) {
        return breakWorldDataLink(worldKey, cloneData, null);
    }

    record OfflineLocation(NamespacedKey worldKey, double x, double y, double z, float yaw, float pitch) {}

    Optional<OfflineLocation> getPlayerLogOffLocation(NamespacedKey worldKey, java.util.UUID playerUuid);

    default org.bukkit.Location getPlayerEntryLocation(@NotNull World targetWorld, @NotNull java.util.UUID playerUuid, boolean preferPreviousLocation) {
        if (preferPreviousLocation) {
            Optional<OfflineLocation> offLoc = getPlayerLogOffLocation(targetWorld.getKey(), playerUuid);
            if (offLoc.isPresent() && offLoc.get().worldKey().equals(targetWorld.getKey())) {
                var off = offLoc.get();
                return getSafeLocation(new org.bukkit.Location(targetWorld, off.x(), off.y(), off.z(), off.yaw(), off.pitch()));
            }
        }
        Location spawn = getSafeSpawnLocation(targetWorld);
        getManagedWorld(targetWorld.getKey()).ifPresent(mw -> {
            mw.getConfiguredSpawnAngles().ifPresent(angles -> {
                spawn.setYaw(angles[0]);
                spawn.setPitch(angles[1]);
            });
        });
        return spawn;
    }

    default org.bukkit.Location getPlayerEntryLocation(@NotNull World targetWorld, @NotNull java.util.UUID playerUuid) {
        return getPlayerEntryLocation(targetWorld, playerUuid, true);
    }
}