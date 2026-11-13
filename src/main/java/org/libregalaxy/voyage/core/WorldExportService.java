package org.libregalaxy.voyage.core;

import me.itsmcb.vexelcore.bukkit.api.text.BukkitMsgBuilder;
import net.kyori.adventure.nbt.CompoundBinaryTag;
import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.NamespacedKey;
import org.bukkit.World;
import org.bukkit.entity.Player;
import org.bukkit.plugin.java.JavaPlugin;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;
import org.libregalaxy.voyage.VoyageContext;
import org.libregalaxy.voyage.api.ManagedWorld;
import org.libregalaxy.voyage.api.VoyageAPI;
import org.libregalaxy.voyage.api.portal.PortalType;
import org.libregalaxy.voyage.api.portal.generator.StructureGenerator;
import org.libregalaxy.voyage.api.portal.generator.VanillaNetherGenerator;
import org.libregalaxy.voyage.api.portal.strategy.VanillaScalingStrategy;
import org.libregalaxy.voyage.core.migration.LevelDatNbt;
import org.libregalaxy.voyage.core.migration.VanillaPlayerData;
import org.libregalaxy.voyage.core.migration.VanillaPlayerStateConverter;
import org.libregalaxy.voyage.core.migration.VanillaWorldExporter;

import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.*;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.concurrent.ConcurrentHashMap;

public class WorldExportService {

    private final VoyageContext context;
    private final JavaPlugin plugin;
    private final VoyageWorldManager worldManager;
    private final WorldFileService fileService;
    private final Set<NamespacedKey> exportingWorlds = ConcurrentHashMap.newKeySet();

    public WorldExportService(@NotNull VoyageContext context, @NotNull VoyageWorldManager worldManager, @NotNull WorldFileService fileService) {
        this.context = context;
        this.plugin = context.getPlugin();
        this.worldManager = worldManager;
        this.fileService = fileService;
    }

    public boolean isExporting(@NotNull NamespacedKey key) {
        return exportingWorlds.contains(key);
    }

    public boolean claimExport(@NotNull NamespacedKey key) {
        return exportingWorlds.add(key);
    }

    public void releaseExport(@NotNull NamespacedKey key) {
        exportingWorlds.remove(key);
    }

    public CompletableFuture<Void> exportWorld(@NotNull NamespacedKey worldKey, @NotNull Path targetZipFile) {
        return exportWorld(worldKey, targetZipFile, null, null, true, true);
    }

    public CompletableFuture<Void> exportWorld(@NotNull NamespacedKey worldKey, @NotNull Path targetZipFile,
                                               @Nullable UUID primaryPlayer, @Nullable String displayName) {
        return exportWorld(worldKey, targetZipFile, primaryPlayer, displayName, true, true);
    }

    public CompletableFuture<Void> exportWorld(@NotNull NamespacedKey worldKey, @NotNull Path targetZipFile,
                                               @Nullable UUID primaryPlayer, @Nullable String displayName,
                                               boolean includeRootFolder) {
        return exportWorld(worldKey, targetZipFile, primaryPlayer, displayName, includeRootFolder, true);
    }

    private static @Nullable World findLoadedBukkitWorld(@NotNull NamespacedKey worldKey) {
        World byKey = Bukkit.getWorld(worldKey);
        if (byKey != null) return byKey;
        if (Bukkit.getWorlds() != null) {
            for (World w : Bukkit.getWorlds()) {
                if (worldKey.equals(w.getKey())) {
                    return w;
                }
            }
        }
        return null;
    }

    private @Nullable World resolveMainWorld() {
        World main = worldManager.getMainLevel();
        if (main != null) {
            return main;
        }
        try {
            List<World> worlds = Bukkit.getWorlds();
            if (worlds != null && !worlds.isEmpty()) {
                return worlds.getFirst();
            }
        } catch (Throwable ignored) {}
        return null;
    }

    public CompletableFuture<Void> exportWorld(@NotNull NamespacedKey worldKey, @NotNull Path targetZipFile,
                                               @Nullable UUID primaryPlayer, @Nullable String displayName,
                                               boolean includeRootFolder,
                                               boolean includePlayerData) {
        World mainWorld = resolveMainWorld();
        if (mainWorld != null && worldKey.equals(mainWorld.getKey())) {
            return CompletableFuture.failedFuture(new IllegalStateException("Cannot export the main level."));
        }

        if (worldManager.getManagedWorld(worldKey).isEmpty() && findLoadedBukkitWorld(worldKey) != null) {
            return CompletableFuture.failedFuture(new IllegalStateException(
                    "World '" + worldKey + "' is loaded but not managed by Voyage. Track it with '/world manage track " + worldKey + "' before exporting."));
        }

        if (!claimExport(worldKey)) {
            return CompletableFuture.failedFuture(new IllegalStateException(
                    "World '" + worldKey + "' is already being exported, cloned, or deleted."));
        }

        CompletableFuture<Void> exportFuture = new CompletableFuture<>();

        Runnable syncPreparation = () -> {
            try {
                World world = findLoadedBukkitWorld(worldKey);
                boolean wasLoaded = world != null;

                // Capture the exact folder while the world is still loaded and actively mounted in Bukkit
                final File worldDir = (world != null) ? world.getWorldFolder() : fileService.resolveWorldFolder(worldKey);

                VanillaPlayerData.LastPosition livePosition = includePlayerData ? snapshotLivePosition(primaryPlayer, world) : null;
                VanillaWorldExporter.WorldBorderData liveBorder = captureWorldBorder(world);
                VanillaWorldExporter.LiveWorldMetadata liveMetadata = captureLiveMetadata(world, worldKey);

                Map<UUID, VanillaPlayerData.LastPosition> liveWorldPositions = new LinkedHashMap<>();
                Map<UUID, CompoundBinaryTag> serverStates = new LinkedHashMap<>();

                final List<UUID> playersToRestore = (world != null)
                        ? world.getPlayers().stream().map(Player::getUniqueId).toList()
                        : List.of();

                if (world != null) {
                    PlayerDataManager pdm = context.getService(PlayerDataManager.class);
                    for (Player p : List.copyOf(world.getPlayers())) {
                        var loc = p.getLocation();
                        liveWorldPositions.put(p.getUniqueId(), new VanillaPlayerData.LastPosition(
                                loc.getX(), loc.getY(), loc.getZ(), loc.getYaw(), loc.getPitch()));
                        try {
                            if (pdm != null) {
                                pdm.savePlayerStateSync(p);
                            }
                        } catch (Throwable t) {
                            plugin.getLogger().warning("Could not save Voyage profile state for "
                                    + p.getName() + " before export: " + t.getMessage());
                        }
                        if (includePlayerData) {
                            try {
                                p.saveData();
                                CompoundBinaryTag state = captureServerPlayerState(p, world);
                                if (state != null) {
                                    serverStates.put(p.getUniqueId(), state);
                                }
                            } catch (Throwable t) {
                                plugin.getLogger().warning("Could not snapshot player data for "
                                        + p.getName() + ": " + t.getMessage());
                            }
                        }
                    }

                    // Synchronously commit all chunk modifications to disk
                    try {
                        world.save();
                    } catch (Exception e) {
                        plugin.getLogger().warning("Failed to save world chunks before export: " + e.getMessage());
                    }
                }

                World fallbackWorld = resolveMainWorld();
                NamespacedKey fallbackKey = fallbackWorld != null ? fallbackWorld.getKey() : null;
                CompletableFuture<Boolean> unloadFuture = wasLoaded
                        ? worldManager.unload(worldKey, fallbackKey, true)
                        : CompletableFuture.completedFuture(true);

                unloadFuture.thenCompose(unloaded -> {
                    if (!unloaded) {
                        return CompletableFuture.failedFuture(new IllegalStateException("Failed to unload world for export."));
                    }

                    return CompletableFuture.runAsync(() -> {
                        try {
                            PlayerDataManager playerDataManager = context.getService(PlayerDataManager.class);
                            Map<UUID, PlayerStateSerializer.PlayerState> rawPlayerStates = (includePlayerData && playerDataManager != null)
                                    ? new LinkedHashMap<>(playerDataManager.getStoredPlayerStates(worldKey))
                                    : new LinkedHashMap<>();

                            List<VanillaPlayerData.ExportedPlayer> players = includePlayerData
                                    ? collectPlayerDataForExport(worldKey, primaryPlayer, livePosition, liveWorldPositions, serverStates)
                                    : List.of();

                            ManagedWorld mw = worldManager.getManagedWorld(worldKey).orElse(null);
                            if (mw instanceof ManagedWorldImpl mwi) {
                                worldManager.ensureWorldGenFiles(worldKey, worldDir, mwi);
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

                            String folderName = includeRootFolder
                                    ? worldKey.asString().replace(':', '_')
                                    : "";

                            VoyageWorldExportData voyageData = compileVoyageExportData(worldKey, mw, world, rawPlayerStates);

                            new VanillaWorldExporter(plugin.getLogger()).export(
                                    worldDir.toPath(),
                                    folderName,
                                    exportName,
                                    voidWorld,
                                    players,
                                    includePlayerData ? primaryPlayer : null,
                                    liveBorder,
                                    liveMetadata,
                                    voyageData,
                                    targetZipFile);

                            pruneOldExports(targetZipFile.getParent());
                        } catch (IOException e) {
                            throw new CompletionException("Failed to export world to zip", e);
                        }
                    }, r -> Bukkit.getScheduler().runTaskAsynchronously(plugin, r));
                }).whenComplete((v, ex) -> {
                    releaseExport(worldKey);
                    if (wasLoaded) {
                        Optional<ManagedWorld> managedWorldOpt = worldManager.getManagedWorld(worldKey);
                        if (managedWorldOpt.isPresent()) {
                            managedWorldOpt.get().load().thenAccept(reloadedWorld -> {
                                Runnable restoreTask = () -> {
                                    for (UUID uuid : playersToRestore) {
                                        Player p = Bukkit.getPlayer(uuid);
                                        if (p != null && p.isOnline()) {
                                            Location returnLoc = worldManager.getPlayerEntryLocation(reloadedWorld, uuid);
                                            p.teleportAsync(returnLoc);
                                            new BukkitMsgBuilder("&7You were brought back to the exported world.").send(p);
                                        }
                                    }
                                };
                                if (Bukkit.isPrimaryThread()) {
                                    restoreTask.run();
                                } else {
                                    Bukkit.getScheduler().runTask(plugin, restoreTask);
                                }
                                if (ex != null) {
                                    exportFuture.completeExceptionally(ex);
                                } else {
                                    exportFuture.complete(null);
                                }
                            }).exceptionally(loadEx -> {
                                plugin.getLogger().warning("Failed to reload world " + worldKey + " after export: " + loadEx.getMessage());
                                if (ex != null) {
                                    exportFuture.completeExceptionally(ex);
                                } else {
                                    exportFuture.complete(null);
                                }
                                return null;
                            });
                            return;
                        }
                    }
                    if (ex != null) {
                        exportFuture.completeExceptionally(ex);
                    } else {
                        exportFuture.complete(null);
                    }
                });
            } catch (Throwable t) {
                releaseExport(worldKey);
                exportFuture.completeExceptionally(t);
            }
        };

        if (Bukkit.isPrimaryThread()) {
            syncPreparation.run();
        } else {
            Bukkit.getScheduler().runTask(plugin, syncPreparation);
        }

        return exportFuture;
    }

    private void pruneOldExports(@Nullable Path exportDir) {
        if (exportDir == null || !Files.isDirectory(exportDir)) return;

        int maxRetained = 5;
        int retentionDays = 7;
        try {
            if (plugin.getConfig() != null) {
                maxRetained = plugin.getConfig().getInt("export.max-retained", 5);
                retentionDays = plugin.getConfig().getInt("export.retention-days", 7);
            }
        } catch (Throwable ignored) {}

        File[] files = exportDir.toFile().listFiles((dir, name) -> name.endsWith(".zip"));
        if (files == null || files.length == 0) return;

        long cutoff = System.currentTimeMillis() - (retentionDays * 86_400_000L);
        Arrays.sort(files, Comparator.comparingLong(File::lastModified));

        int remaining = files.length;
        for (File f : files) {
            boolean tooOld = retentionDays > 0 && f.lastModified() < cutoff;
            boolean exceedsCap = maxRetained > 0 && remaining > maxRetained;
            if (tooOld || exceedsCap) {
                if (f.delete()) {
                    plugin.getLogger().info("Pruned old export archive: " + f.getName());
                    remaining--;
                }
            }
        }
    }

    private @NotNull VoyageWorldExportData compileVoyageExportData(
            @NotNull NamespacedKey worldKey,
            @Nullable ManagedWorld mw,
            @Nullable World world,
            @NotNull Map<UUID, PlayerStateSerializer.PlayerState> playerStates
    ) {
        String env = mw != null ? mw.getEnvironment().name() : (world != null ? world.getEnvironment().name() : "NORMAL");
        long seed = mw != null ? mw.getSeed() : (world != null ? world.getSeed() : 0L);
        String worldType = mw != null ? mw.getWorldType().name() : "NORMAL";
        String generator = mw != null ? mw.getGenerator().orElse(null) : null;
        String generatorSettings = mw != null ? mw.getGeneratorSettings().orElse(null) : null;
        boolean autoLoad = mw != null && mw.isAutoLoad();
        String difficulty = mw != null ? mw.getDifficulty().name() : (world != null ? world.getDifficulty().name() : "NORMAL");
        boolean generateStructures = mw == null || mw.isGenerateStructures();

        int[] spawn = null;
        if (mw != null && mw.getConfiguredSpawn().isPresent()) {
            spawn = mw.getConfiguredSpawn().get();
        } else if (world != null) {
            spawn = new int[]{world.getSpawnLocation().getBlockX(), world.getSpawnLocation().getBlockY(), world.getSpawnLocation().getBlockZ()};
        }

        float[] spawnAngle = null;
        if (mw != null && mw.getConfiguredSpawnAngles().isPresent()) {
            spawnAngle = mw.getConfiguredSpawnAngles().get();
        } else if (world != null) {
            spawnAngle = new float[]{world.getSpawnLocation().getYaw(), world.getSpawnLocation().getPitch()};
        }

        Map<String, String> gameRulesMap = new LinkedHashMap<>();
        if (mw != null) {
            mw.getGameRules().forEach((r, val) -> gameRulesMap.put(r.getKey().value(), String.valueOf(val)));
        }

        Map<String, String> metadata = mw != null ? mw.getAllMetadata() : Map.of();

        List<VoyageWorldExportData.PortalLinkData> portalLinks = new ArrayList<>();
        if (mw != null) {
            for (PortalType pt : PortalType.values()) {
                mw.getPortalLink(pt).ifPresent(pl -> {
                    String strat = (pl.coordinateStrategy() instanceof VanillaScalingStrategy) ? "VANILLA_SCALING" : "TARGET_SPAWN_POINT";
                    Double factor = (pl.coordinateStrategy() instanceof VanillaScalingStrategy vs) ? vs.getScaleFactor() : null;
                    String gen = "NONE";
                    String structureKey = null;
                    if (pl.generator() instanceof VanillaNetherGenerator) {
                        gen = "VANILLA_NETHER";
                    } else if (pl.generator() instanceof StructureGenerator sg) {
                        gen = "STRUCTURE";
                        structureKey = sg.getStructureKey().asString();
                    }
                    portalLinks.add(new VoyageWorldExportData.PortalLinkData(
                            pt.name(), pl.targetWorld().asString(), strat, factor, gen, structureKey
                    ));
                });
            }
        }

        return VoyageWorldExportData.create(
                worldKey.asString(),
                env,
                seed,
                worldType,
                generator,
                generatorSettings,
                autoLoad,
                difficulty,
                generateStructures,
                spawn,
                spawnAngle,
                gameRulesMap,
                metadata,
                portalLinks,
                playerStates
        );
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
                    border.getWarningTimeTicks() / 20);
        } catch (Throwable t) {
            return null;
        }
    }

    private @Nullable VanillaWorldExporter.LiveWorldMetadata captureLiveMetadata(@Nullable World world, @NotNull NamespacedKey worldKey) {
        int spawnX = 0, spawnY = 64, spawnZ = 0;
        float spawnYaw = 0.0f;
        int difficultyId = 2; // NORMAL
        long dayTime = 0L;
        int gameType = 1; // CREATIVE

        if (world != null) {
            var spawn = world.getSpawnLocation();
            spawnX = spawn.getBlockX();
            spawnY = spawn.getBlockY();
            spawnZ = spawn.getBlockZ();
            spawnYaw = spawn.getYaw();
            difficultyId = switch (world.getDifficulty()) {
                case PEACEFUL -> 0;
                case EASY -> 1;
                case HARD -> 3;
                default -> 2;
            };
            dayTime = world.getTime();
        } else {
            ManagedWorld mw = worldManager.getManagedWorld(worldKey).orElse(null);
            if (mw != null) {
                var spawn = mw.getConfiguredSpawn();
                if (spawn.isPresent()) {
                    int[] s = spawn.get();
                    spawnX = s[0];
                    spawnY = s[1];
                    spawnZ = s[2];
                }
                difficultyId = switch (mw.getDifficulty()) {
                    case PEACEFUL -> 0;
                    case EASY -> 1;
                    case HARD -> 3;
                    default -> 2;
                };
            }
        }
        return new VanillaWorldExporter.LiveWorldMetadata(spawnX, spawnY, spawnZ, spawnYaw, difficultyId, dayTime, gameType);
    }

    private void patchWorldBorder(@NotNull Path worldDir,
                                  @NotNull VanillaWorldExporter.WorldBorderData border) {
        try {
            Path levelDat = worldDir.resolve("level.dat");
            if (!Files.isRegularFile(levelDat)) return;
            CompoundBinaryTag root = LevelDatNbt.read(levelDat);
            root = VanillaWorldExporter.applyWorldBorder(root, border);
            LevelDatNbt.writeVanillaSafe(levelDat, root, null);
        } catch (Exception e) {
            plugin.getLogger().warning("Could not persist the live world border for export: " + e.getMessage());
        }
    }

    private @NotNull List<VanillaPlayerData.ExportedPlayer> collectPlayerDataForExport(
            @NotNull NamespacedKey worldKey, @Nullable UUID primaryPlayer,
            @Nullable VanillaPlayerData.LastPosition livePosition,
            @NotNull Map<UUID, VanillaPlayerData.LastPosition> liveWorldPositions,
            @NotNull Map<UUID, CompoundBinaryTag> serverStates) {
        List<VanillaPlayerData.ExportedPlayer> players = new ArrayList<>();
        int fromServer = 0;
        int fromProfile = 0;
        try {
            PlayerDataManager playerDataManager = context.getService(PlayerDataManager.class);
            Map<UUID, PlayerStateSerializer.PlayerState> states = playerDataManager != null
                    ? new LinkedHashMap<>(playerDataManager.getStoredPlayerStates(worldKey))
                    : new LinkedHashMap<>();

            for (UUID serverUuid : serverStates.keySet()) {
                states.putIfAbsent(serverUuid, null);
            }

            for (Map.Entry<UUID, PlayerStateSerializer.PlayerState> entry : states.entrySet()) {
                UUID uuid = entry.getKey();
                PlayerStateSerializer.PlayerState state = entry.getValue();
                try {
                    CompoundBinaryTag serverState = serverStates.get(uuid);

                    CompoundBinaryTag converted;
                    VanillaPlayerData.LastPosition position = (livePosition != null && uuid.equals(primaryPlayer))
                            ? livePosition
                            : liveWorldPositions.get(uuid);

                    if (position == null && playerDataManager != null) {
                        position = resolveStoredPosition(playerDataManager, worldKey, uuid);
                    }

                    if (serverState != null) {
                        converted = serverState;
                        fromServer++;
                    } else if (state != null) {
                        converted = VanillaPlayerStateConverter.toVanillaPlayerTag(state, worldKey.asString());
                        if (state.inventory != null && !converted.keySet().contains("Inventory")) {
                            plugin.getLogger().warning("Exported player " + uuid
                                    + " without items (inventory empty or unreadable). Item blob head: "
                                    + hexPreview(state.inventory));
                        }
                        fromProfile++;
                    } else {
                        continue;
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
                world.getWorldFolder().toPath().resolve("players").resolve("data").resolve(fileName),
                world.getWorldFolder().toPath().resolve("playerdata").resolve(fileName),
                Bukkit.getServer().getLevelDirectory().resolve("players").resolve("data").resolve(fileName),
                Bukkit.getServer().getLevelDirectory().resolve("playerdata").resolve(fileName)
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
}