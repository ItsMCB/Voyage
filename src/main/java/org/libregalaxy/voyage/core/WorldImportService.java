package org.libregalaxy.voyage.core;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import net.kyori.adventure.nbt.*;
import org.bukkit.Bukkit;
import org.bukkit.Difficulty;
import org.bukkit.GameRule;
import org.bukkit.NamespacedKey;
import org.bukkit.Registry;
import org.bukkit.World;
import org.bukkit.WorldType;
import org.bukkit.plugin.java.JavaPlugin;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;
import org.libregalaxy.voyage.api.ManagedWorld;
import org.libregalaxy.voyage.api.VoyageAPI;
import org.libregalaxy.voyage.api.portal.PortalLink;
import org.libregalaxy.voyage.api.portal.PortalType;
import org.libregalaxy.voyage.api.portal.generator.NoneGenerator;
import org.libregalaxy.voyage.api.portal.generator.VanillaNetherGenerator;
import org.libregalaxy.voyage.api.portal.strategy.FixedSpawnStrategy;
import org.libregalaxy.voyage.api.portal.strategy.VanillaScalingStrategy;
import org.libregalaxy.voyage.core.migration.LegacyWorldMigrator;
import org.libregalaxy.voyage.core.migration.LevelDatNbt;
import org.libregalaxy.voyage.core.migration.WorldFormatDetector;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.*;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.logging.Logger;

public class WorldImportService {

    private final JavaPlugin plugin;
    private final VoyageWorldManager manager;
    private final WorldFormatDetector formatDetector = new WorldFormatDetector();
    private final LegacyWorldMigrator legacyMigrator;

    public WorldImportService(JavaPlugin plugin, VoyageWorldManager manager) {
        this.plugin = plugin;
        this.manager = manager;
        this.legacyMigrator = new LegacyWorldMigrator(resolveLogger(plugin));
    }

    private static @NotNull Logger resolveLogger(@Nullable JavaPlugin plugin) {
        if (plugin != null) {
            try {
                Logger logger = plugin.getLogger();
                if (logger != null) return logger;
            } catch (Throwable mockOrProxyFailure) {
                // Mocked plugin whose real getLogger() implementation is unusable.
            }
        }
        return Logger.getLogger("Voyage");
    }

    public record DimensionGenInfo(
            @NotNull WorldType worldType,
            @Nullable String generatorSettingsJson,
            @Nullable String generatorPluginName,
            @NotNull World.Environment environment,
            long seed,
            boolean generateStructures
    ) {}

    public record ParsedWorldData(
            @Nullable CompoundBinaryTag rawLevelData,
            int dataVersion,
            long globalSeed,
            boolean globalGenerateStructures,
            @NotNull Map<String, DimensionGenInfo> dimensionGenerators
    ) {}

    public CompletableFuture<VoyageAPI.ImportStrategy> importWorld(
            @NotNull Path sourceDir,
            @NotNull NamespacedKey targetKey,
            boolean autoLink,
            @Nullable String dataProfileId,
            @Nullable VoyageAPI.DataLinkMergeStrategy mergeStrategy,
            boolean deleteSourceAfterImport,
            @Nullable World.Environment environmentOverride,
            @Nullable String generatorOverride,
            @Nullable String generatorSettingsOverride
    ) {
        return importWorld(sourceDir, targetKey, true, autoLink, dataProfileId, mergeStrategy, deleteSourceAfterImport, environmentOverride, generatorOverride, generatorSettingsOverride);
    }

    public CompletableFuture<VoyageAPI.ImportStrategy> importWorld(
            @NotNull Path sourceDir,
            @NotNull NamespacedKey targetKey,
            boolean autoLoad,
            boolean autoLink,
            @Nullable String dataProfileId,
            @Nullable VoyageAPI.DataLinkMergeStrategy mergeStrategy,
            boolean deleteSourceAfterImport,
            @Nullable World.Environment environmentOverride,
            @Nullable String generatorOverride,
            @Nullable String generatorSettingsOverride
    ) {
        if (manager.worldExists(targetKey)) {
            return CompletableFuture.failedFuture(new IllegalStateException("Dimension " + targetKey.asString() + " already exists."));
        }

        CompletableFuture<VoyageAPI.ImportStrategy> resultFuture = new CompletableFuture<>();

        List<NamespacedKey> createdKeys = Collections.synchronizedList(new ArrayList<>());
        List<Path> stagedPaths = new ArrayList<>();

        Bukkit.getScheduler().runTaskAsynchronously(plugin, () -> {
            try {
                VoyageAPI.ImportStrategy strategy = formatDetector.detect(sourceDir);
                plugin.getLogger().info("Detected import strategy: " + strategy.getDisplayName() + " for " + sourceDir.getFileName());

                Path overworldSource = sourceDir;
                Path netherSource = null;
                Path endSource = null;

                Path paperDims = sourceDir.resolve("dimensions");
                if (Files.isDirectory(paperDims)) {
                    Path nsDir = null;
                    try (var stream = Files.list(paperDims)) {
                        nsDir = stream.filter(Files::isDirectory).findFirst().orElse(null);
                    } catch (IOException ignored) {}

                    if (nsDir != null) {
                        if (Files.isDirectory(nsDir.resolve("overworld"))) overworldSource = nsDir.resolve("overworld");
                        if (Files.isDirectory(nsDir.resolve("the_nether"))) netherSource = nsDir.resolve("the_nether");
                        if (Files.isDirectory(nsDir.resolve("the_end"))) endSource = nsDir.resolve("the_end");

                        if (overworldSource == sourceDir) {
                            try (var stream = Files.list(nsDir)) {
                                overworldSource = stream.filter(Files::isDirectory).findFirst().orElse(sourceDir);
                            } catch (IOException ignored) {}
                        }
                    }
                } else if (Files.isDirectory(sourceDir.resolve("region"))) {
                    if (Files.isDirectory(sourceDir.resolve("DIM-1"))) netherSource = sourceDir.resolve("DIM-1");
                    if (Files.isDirectory(sourceDir.resolve("DIM1"))) endSource = sourceDir.resolve("DIM1");
                }

                boolean isMultiDim = netherSource != null || endSource != null;

                Path dimsRoot = Bukkit.getServer().getLevelDirectory().resolve("dimensions");
                String ns = targetKey.getNamespace();
                String baseKey = targetKey.getKey();

                ParsedWorldData parsedWorldData = parseWorldData(sourceDir, environmentOverride, generatorOverride, generatorSettingsOverride);
                CompoundBinaryTag borderData = extractBorderData(sourceDir);

                List<CompletableFuture<Void>> creationFutures = new ArrayList<>();

                if (isMultiDim) {
                    NamespacedKey netherKey = NamespacedKey.fromString(ns + ":" + baseKey + "_nether");
                    NamespacedKey endKey = NamespacedKey.fromString(ns + ":" + baseKey + "_end");

                    if (netherKey != null && netherSource != null && manager.worldExists(netherKey)) {
                        throw new IllegalStateException("Nether dimension already exists: " + netherKey);
                    }
                    if (endKey != null && endSource != null && manager.worldExists(endKey)) {
                        throw new IllegalStateException("End dimension already exists: " + endKey);
                    }

                    DimensionGenInfo overworldInfo = resolveDimensionInfo(parsedWorldData, "minecraft:overworld",
                            environmentOverride != null ? environmentOverride : World.Environment.NORMAL);
                    Path overworldTarget = dimsRoot.resolve(ns).resolve(baseKey);
                    legacyMigrator.copyDimensionPayload(overworldSource, overworldTarget);
                    migrateLegacyBorderOnDisk(overworldTarget);
                    stagedPaths.add(overworldTarget);
                    writeDimensionGenFiles(overworldTarget, targetKey, overworldInfo, parsedWorldData);

                    boolean hasNether = netherKey != null && netherSource != null;
                    boolean hasEnd = endKey != null && endSource != null;

                    DimensionGenInfo netherInfo = hasNether
                            ? resolveDimensionInfo(parsedWorldData, "minecraft:the_nether", World.Environment.NETHER) : null;
                    if (hasNether) {
                        Path netherTarget = dimsRoot.resolve(netherKey.getNamespace()).resolve(netherKey.getKey());
                        legacyMigrator.copyDimensionPayload(netherSource, netherTarget);
                        migrateLegacyBorderOnDisk(netherTarget);
                        stagedPaths.add(netherTarget);
                        writeDimensionGenFiles(netherTarget, netherKey, netherInfo, parsedWorldData);
                    }

                    DimensionGenInfo endInfo = hasEnd
                            ? resolveDimensionInfo(parsedWorldData, "minecraft:the_end", World.Environment.THE_END) : null;
                    if (hasEnd) {
                        Path endTarget = dimsRoot.resolve(endKey.getNamespace()).resolve(endKey.getKey());
                        legacyMigrator.copyDimensionPayload(endSource, endTarget);
                        migrateLegacyBorderOnDisk(endTarget);
                        stagedPaths.add(endTarget);
                        writeDimensionGenFiles(endTarget, endKey, endInfo, parsedWorldData);
                    }

                    CompletableFuture<Void> overworldFuture = new CompletableFuture<>();
                    CompletableFuture<Void> netherFuture = hasNether ? new CompletableFuture<>() : null;
                    CompletableFuture<Void> endFuture = hasEnd ? new CompletableFuture<>() : null;

                    Bukkit.getScheduler().runTask(plugin, () -> {
                        createAndRestoreWorld(targetKey, overworldInfo, parsedWorldData.rawLevelData(), borderData, autoLoad).thenCompose(mw -> {
                            createdKeys.add(targetKey);
                            if (autoLink && hasNether) {
                                mw.linkPortal(PortalType.NETHER, new PortalLink(netherKey, new VanillaScalingStrategy(8.0), new VanillaNetherGenerator()));
                            }
                            if (autoLink && hasEnd) {
                                mw.linkPortal(PortalType.END, new PortalLink(endKey, new FixedSpawnStrategy(), new NoneGenerator()));
                            }
                            return applyDataProfile(targetKey, dataProfileId, mergeStrategy);
                        }).whenComplete((v, ex) -> completeFrom(overworldFuture, ex));

                        if (hasNether) {
                            createAndRestoreWorld(netherKey, netherInfo, null, null, autoLoad).thenCompose(mw -> {
                                createdKeys.add(netherKey);
                                if (autoLink) {
                                    mw.linkPortal(PortalType.NETHER, new PortalLink(targetKey, new VanillaScalingStrategy(0.125), new VanillaNetherGenerator()));
                                }
                                return applyDataProfile(netherKey, dataProfileId, mergeStrategy);
                            }).whenComplete((v, ex) -> completeFrom(netherFuture, ex));
                        }

                        if (hasEnd) {
                            createAndRestoreWorld(endKey, endInfo, null, null, autoLoad).thenCompose(mw -> {
                                createdKeys.add(endKey);
                                if (autoLink) {
                                    mw.linkPortal(PortalType.END, new PortalLink(targetKey, new FixedSpawnStrategy(), new NoneGenerator()));
                                }
                                return applyDataProfile(endKey, dataProfileId, mergeStrategy);
                            }).whenComplete((v, ex) -> completeFrom(endFuture, ex));
                        }
                    });

                    creationFutures.add(overworldFuture);
                    if (netherFuture != null) creationFutures.add(netherFuture);
                    if (endFuture != null) creationFutures.add(endFuture);
                } else {
                    World.Environment env = environmentOverride != null ? environmentOverride : World.Environment.NORMAL;
                    DimensionGenInfo dimInfo = resolveDimensionInfo(parsedWorldData, "minecraft:overworld", env);

                    Path overworldTarget = dimsRoot.resolve(ns).resolve(baseKey);
                    legacyMigrator.copyDimensionPayload(overworldSource, overworldTarget);
                    migrateLegacyBorderOnDisk(overworldTarget);
                    stagedPaths.add(overworldTarget);
                    writeDimensionGenFiles(overworldTarget, targetKey, dimInfo, parsedWorldData);

                    CompletableFuture<Void> singleFuture = new CompletableFuture<>();
                    Bukkit.getScheduler().runTask(plugin, () -> {
                        createAndRestoreWorld(targetKey, dimInfo, parsedWorldData.rawLevelData(), borderData, autoLoad).thenCompose(mw -> {
                            createdKeys.add(targetKey);
                            return applyDataProfile(targetKey, dataProfileId, mergeStrategy);
                        }).whenComplete((v, ex) -> completeFrom(singleFuture, ex));
                    });
                    creationFutures.add(singleFuture);
                }

                CompletableFuture.allOf(creationFutures.toArray(new CompletableFuture[0]))
                        .thenCompose(v -> {
                            if (deleteSourceAfterImport) {
                                return manager.deleteDirectory(sourceDir.toFile());
                            }
                            return CompletableFuture.completedFuture(null);
                        })
                        .whenComplete((v, ex) -> {
                            if (ex != null) {
                                rollbackImport(resultFuture, targetKey, createdKeys, stagedPaths, ex);
                            } else {
                                resultFuture.complete(strategy);
                            }
                        });
            } catch (Exception e) {
                rollbackImport(resultFuture, targetKey, createdKeys, stagedPaths, e);
            }
        });

        return resultFuture;
    }

    private void migrateLegacyBorderOnDisk(@NotNull Path targetDir) {
        try {
            Path legacyBorder = targetDir.resolve("data").resolve("world_border.dat");
            if (Files.isRegularFile(legacyBorder)) {
                Path modernBorderDir = targetDir.resolve("data").resolve("minecraft");
                Files.createDirectories(modernBorderDir);
                Path modernBorder = modernBorderDir.resolve("world_border.dat");
                if (!Files.exists(modernBorder)) {
                    Files.move(legacyBorder, modernBorder, StandardCopyOption.REPLACE_EXISTING);
                } else {
                    Files.deleteIfExists(legacyBorder);
                }
            }
        } catch (Exception e) {
            plugin.getLogger().warning("Failed to clean up legacy world_border.dat on disk: " + e.getMessage());
        }
    }

    private static void completeFrom(CompletableFuture<Void> future, @Nullable Throwable ex) {
        if (ex != null) {
            future.completeExceptionally(ex);
        } else {
            future.complete(null);
        }
    }

    private CompletableFuture<Void> applyDataProfile(@NotNull NamespacedKey key,
                                                     @Nullable String dataProfileId,
                                                     @Nullable VoyageAPI.DataLinkMergeStrategy mergeStrategy) {
        if (dataProfileId == null) {
            return CompletableFuture.completedFuture(null);
        }
        return manager.getContext().getService(PlayerDataManager.class)
                .setProfileId(key, dataProfileId,
                        mergeStrategy != null ? mergeStrategy : VoyageAPI.DataLinkMergeStrategy.DESTINATION_PRIORITY, null);
    }

    private void rollbackImport(@NotNull CompletableFuture<VoyageAPI.ImportStrategy> resultFuture,
                                @NotNull NamespacedKey targetKey,
                                @NotNull List<NamespacedKey> createdKeys,
                                @NotNull List<Path> stagedPaths,
                                @NotNull Throwable cause) {
        plugin.getLogger().severe("Import failed, rolling back: " + cause.getMessage());

        List<CompletableFuture<?>> rollbackFutures = new ArrayList<>();
        for (NamespacedKey createdKey : createdKeys) {
            plugin.getLogger().warning("Rolling back created world: " + createdKey.asString());
            rollbackFutures.add(manager.deleteWorld(createdKey, null).exceptionally(ex -> false));
        }
        for (Path p : stagedPaths) {
            rollbackFutures.add(manager.deleteDirectory(p.toFile()));
        }

        CompletableFuture.allOf(rollbackFutures.toArray(new CompletableFuture[0])).whenComplete((v, ex) ->
                resultFuture.completeExceptionally(new CompletionException("Failed to import world " + targetKey, cause)));
    }

    public @NotNull ParsedWorldData parseWorldData(@NotNull Path sourceDir, @Nullable World.Environment defaultEnv) {
        return parseWorldData(sourceDir, defaultEnv, null, null);
    }

    public @NotNull ParsedWorldData parseWorldData(
            @NotNull Path sourceDir,
            @Nullable World.Environment defaultEnv,
            @Nullable String generatorOverride,
            @Nullable String generatorSettingsOverride
    ) {
        CompoundBinaryTag levelData = null;
        int dataVersion = 0;
        long globalSeed = 0L;
        boolean globalGenerateStructures = true;
        Map<String, DimensionGenInfo> dims = new LinkedHashMap<>();

        boolean isExplicitVoid = VoyageAPI.isVoidWorld(generatorOverride, generatorSettingsOverride);

        Path wgsFile = findWorldGenSettingsFile(sourceDir);
        CompoundBinaryTag worldGenCompound = null;

        if (wgsFile != null) {
            try {
                CompoundBinaryTag root = LevelDatNbt.read(wgsFile);
                worldGenCompound = root.keySet().contains("data") ? root.getCompound("data") : root;
                if (root.keySet().contains("DataVersion")) {
                    dataVersion = root.getInt("DataVersion", 0);
                }
            } catch (Exception e) {
                plugin.getLogger().warning("Failed to parse " + wgsFile + ": " + e.getMessage());
            }
        }

        Path levelDatPath = sourceDir.resolve("level.dat");
        if (!Files.exists(levelDatPath)) {
            levelDatPath = sourceDir.resolve("level.dat_old");
        }

        if (Files.exists(levelDatPath)) {
            try {
                CompoundBinaryTag root = LevelDatNbt.read(levelDatPath);
                levelData = root.keySet().contains("Data") ? root.getCompound("Data") : root;

                if (dataVersion == 0) {
                    dataVersion = levelData.getInt("DataVersion", root.getInt("DataVersion", 0));
                }

                if (worldGenCompound == null && levelData.keySet().contains("WorldGenSettings")) {
                    worldGenCompound = levelData.getCompound("WorldGenSettings");
                }
            } catch (Exception e) {
                plugin.getLogger().warning("Could not parse " + levelDatPath.getFileName() + " for metadata: " + e.getMessage());
            }
        }

        if (worldGenCompound != null) {
            globalSeed = worldGenCompound.getLong("seed", levelData != null ? levelData.getLong("RandomSeed", 0L) : 0L);

            if (worldGenCompound.keySet().contains("generate_structures")) {
                globalGenerateStructures = getBooleanTag(worldGenCompound.get("generate_structures"), true);
            } else if (worldGenCompound.keySet().contains("generate_features")) {
                globalGenerateStructures = getBooleanTag(worldGenCompound.get("generate_features"), true);
            }

            if (worldGenCompound.keySet().contains("dimensions")) {
                CompoundBinaryTag dimsCompound = worldGenCompound.getCompound("dimensions");
                for (Map.Entry<String, ? extends BinaryTag> entry : dimsCompound) {
                    if (entry.getValue() instanceof CompoundBinaryTag dimTag) {
                        DimensionGenInfo info = parseDimensionGenTag(entry.getKey(), dimTag, globalSeed, globalGenerateStructures);
                        if (isExplicitVoid) {
                            info = new DimensionGenInfo(WorldType.FLAT, VoyageAPI.VANILLA_VOID_SETTINGS, "Voyage:void", info.environment(), info.seed(), false);
                        } else if (generatorSettingsOverride != null) {
                            info = new DimensionGenInfo(WorldType.FLAT, generatorSettingsOverride, generatorOverride, info.environment(), info.seed(), info.generateStructures());
                        } else if (generatorOverride != null) {
                            info = new DimensionGenInfo(info.worldType(), info.generatorSettingsJson(), generatorOverride, info.environment(), info.seed(), info.generateStructures());
                        }
                        dims.put(entry.getKey(), info);
                    }
                }
            }
        } else if (levelData != null) {
            globalSeed = levelData.getLong("RandomSeed", 0L);
            String generatorName = levelData.getString("generatorName", "default").toLowerCase(Locale.ROOT);
            String generatorOptions = levelData.getString("generatorOptions", "");

            boolean isVoidGen = isExplicitVoid
                    || VoyageAPI.isVoidGenerator(generatorName)
                    || VoyageAPI.isVoidSettings(generatorOptions);

            WorldType type = isVoidGen ? WorldType.FLAT : switch (generatorName) {
                case "flat" -> WorldType.FLAT;
                case "amplified" -> WorldType.AMPLIFIED;
                case "largebiomes", "large_biomes" -> WorldType.LARGE_BIOMES;
                default -> WorldType.NORMAL;
            };

            String parsedLegacySettings = (type == WorldType.FLAT) ? parseLegacyFlatString(generatorOptions) : null;
            if (generatorSettingsOverride != null && !isVoidGen) {
                parsedLegacySettings = generatorSettingsOverride;
                type = WorldType.FLAT;
            }
            boolean isVoid = isVoidGen || VoyageAPI.isVoidSettings(parsedLegacySettings);
            String settingsJson = isVoid ? VoyageAPI.VANILLA_VOID_SETTINGS : parsedLegacySettings;

            DimensionGenInfo fallback = new DimensionGenInfo(
                    type,
                    settingsJson,
                    isVoid ? "Voyage:void" : (generatorOverride != null ? generatorOverride : (generatorName.startsWith("minecraft:") || generatorName.equals("default") || generatorName.equals("flat") ? null : generatorName)),
                    defaultEnv != null ? defaultEnv : World.Environment.NORMAL,
                    globalSeed,
                    !isVoid && globalGenerateStructures
            );
            dims.put("minecraft:overworld", fallback);
        } else {
            DimensionGenInfo fallback = new DimensionGenInfo(
                    isExplicitVoid || generatorSettingsOverride != null ? WorldType.FLAT : WorldType.NORMAL,
                    isExplicitVoid ? VoyageAPI.VANILLA_VOID_SETTINGS : generatorSettingsOverride,
                    isExplicitVoid ? "Voyage:void" : generatorOverride,
                    defaultEnv != null ? defaultEnv : World.Environment.NORMAL,
                    0L,
                    !isExplicitVoid
            );
            dims.put("minecraft:overworld", fallback);
        }

        return new ParsedWorldData(levelData, dataVersion, globalSeed, globalGenerateStructures, dims);
    }

    private @Nullable Path findWorldGenSettingsFile(@NotNull Path sourceDir) {
        Path p1 = sourceDir.resolve("data").resolve("minecraft").resolve("world_gen_settings.dat");
        if (Files.exists(p1)) return p1;
        Path p2 = sourceDir.resolve("world_gen_settings.dat");
        if (Files.exists(p2)) return p2;
        Path p3 = sourceDir.resolve("dimensions").resolve("minecraft").resolve("overworld").resolve("data").resolve("minecraft").resolve("world_gen_settings.dat");
        if (Files.exists(p3)) return p3;
        return null;
    }

    private @NotNull DimensionGenInfo parseDimensionGenTag(
            @NotNull String dimKey,
            @NotNull CompoundBinaryTag dimTag,
            long fallbackSeed,
            boolean fallbackStructures
    ) {
        String dimType = dimTag.getString("type", dimKey);
        World.Environment env = resolveEnvironmentFromType(dimType);

        if (!dimTag.keySet().contains("generator")) {
            return new DimensionGenInfo(WorldType.NORMAL, null, null, env, fallbackSeed, fallbackStructures);
        }

        CompoundBinaryTag genTag = dimTag.getCompound("generator");
        String genType = genTag.getString("type", "minecraft:noise");

        if (VoyageAPI.isVoidGenerator(genType)) {
            return new DimensionGenInfo(WorldType.FLAT, VoyageAPI.VANILLA_VOID_SETTINGS, "Voyage:void", env, fallbackSeed, false);
        }

        return switch (genType) {
            case "minecraft:flat" -> {
                String settingsJson = serializeFlatSettingsToJson(genTag.get("settings"));
                boolean isVoid = VoyageAPI.isVoidSettings(settingsJson);
                boolean structures = !isVoid && fallbackStructures;
                yield new DimensionGenInfo(WorldType.FLAT, isVoid ? VoyageAPI.VANILLA_VOID_SETTINGS : settingsJson, isVoid ? "Voyage:void" : null, env, fallbackSeed, structures);
            }
            case "minecraft:noise" -> {
                WorldType type = WorldType.NORMAL;
                BinaryTag settingsTag = genTag.get("settings");
                if (settingsTag instanceof StringBinaryTag strTag) {
                    String s = strTag.value();
                    if (s.contains("amplified")) type = WorldType.AMPLIFIED;
                    else if (s.contains("large_biomes")) type = WorldType.LARGE_BIOMES;
                }
                yield new DimensionGenInfo(type, null, null, env, fallbackSeed, fallbackStructures);
            }
            default -> {
                if (genType.startsWith("minecraft:")) {
                    plugin.getLogger().warning("Unrecognized vanilla generator type '" + genType + "' for " + dimKey + ". Defaulting to NORMAL terrain.");
                    yield new DimensionGenInfo(WorldType.NORMAL, null, null, env, fallbackSeed, fallbackStructures);
                } else {
                    yield new DimensionGenInfo(WorldType.NORMAL, null, genType, env, fallbackSeed, fallbackStructures);
                }
            }
        };
    }

    private @NotNull World.Environment resolveEnvironmentFromType(@NotNull String type) {
        String lower = type.toLowerCase(Locale.ROOT);
        if (lower.contains("nether")) return World.Environment.NETHER;
        if (lower.contains("end")) return World.Environment.THE_END;
        return World.Environment.NORMAL;
    }

    public @NotNull DimensionGenInfo resolveDimensionInfo(
            @NotNull ParsedWorldData data,
            @NotNull String preferredDimKey,
            @NotNull World.Environment targetEnv
    ) {
        if (data.dimensionGenerators().containsKey(preferredDimKey)) {
            return data.dimensionGenerators().get(preferredDimKey);
        }

        for (Map.Entry<String, DimensionGenInfo> entry : data.dimensionGenerators().entrySet()) {
            if (entry.getValue().environment() == targetEnv) {
                return entry.getValue();
            }
        }

        if (data.dimensionGenerators().containsKey("minecraft:overworld")) {
            return data.dimensionGenerators().get("minecraft:overworld");
        }

        if (!data.dimensionGenerators().isEmpty()) {
            return data.dimensionGenerators().values().iterator().next();
        }

        return new DimensionGenInfo(WorldType.NORMAL, null, null, targetEnv, data.globalSeed(), data.globalGenerateStructures());
    }

    public static @Nullable String serializeFlatSettingsToJson(@Nullable BinaryTag settingsTag) {
        if (settingsTag == null) return null;

        if (settingsTag instanceof StringBinaryTag strTag) {
            String val = strTag.value();
            if (val.trim().startsWith("{")) return val;
            return parseLegacyFlatString(val);
        }

        if (settingsTag instanceof CompoundBinaryTag compound) {
            JsonObject root = new JsonObject();

            JsonArray layersArray = new JsonArray();
            if (compound.keySet().contains("layers") && compound.get("layers") instanceof ListBinaryTag list) {
                for (BinaryTag elem : list) {
                    if (elem instanceof CompoundBinaryTag layerComp) {
                        JsonObject layerObj = new JsonObject();
                        String block = layerComp.getString("block", "minecraft:air");
                        if (!block.contains(":")) block = "minecraft:" + block;
                        int height = layerComp.getInt("height", 1);
                        layerObj.addProperty("block", block);
                        layerObj.addProperty("height", height);
                        layersArray.add(layerObj);
                    }
                }
            }

            if (layersArray.isEmpty()) {
                String biome = compound.getString("biome", "minecraft:plains");
                JsonObject defaultLayer = new JsonObject();
                defaultLayer.addProperty("block", biome.contains("void") ? "minecraft:air" : "minecraft:bedrock");
                defaultLayer.addProperty("height", 1);
                layersArray.add(defaultLayer);
            }
            root.add("layers", layersArray);

            String biome = compound.getString("biome", "minecraft:plains");
            if (!biome.contains(":")) biome = "minecraft:" + biome;
            root.addProperty("biome", biome);

            root.addProperty("features", getBooleanTag(compound.get("features"), false));
            root.addProperty("lakes", getBooleanTag(compound.get("lakes"), false));

            return root.toString();
        }

        return null;
    }

    public static @NotNull CompoundBinaryTag flatJsonToNbt(@NotNull String json) {
        try {
            JsonObject root = JsonParser.parseString(json).getAsJsonObject();
            CompoundBinaryTag.Builder builder = CompoundBinaryTag.builder();

            if (root.has("layers") && root.get("layers").isJsonArray()) {
                ListBinaryTag.Builder<CompoundBinaryTag> listBuilder = ListBinaryTag.builder(BinaryTagTypes.COMPOUND);
                for (JsonElement elem : root.getAsJsonArray("layers")) {
                    if (elem.isJsonObject()) {
                        JsonObject l = elem.getAsJsonObject();
                        String block = l.has("block") ? l.get("block").getAsString() : "minecraft:air";
                        int height = l.has("height") ? l.get("height").getAsInt() : 1;
                        listBuilder.add(CompoundBinaryTag.builder()
                                .putString("block", block)
                                .putInt("height", height)
                                .build());
                    }
                }
                builder.put("layers", listBuilder.build());
            }

            String biome = root.has("biome") ? root.get("biome").getAsString() : "minecraft:plains";
            builder.putString("biome", biome);

            boolean features = root.has("features") && root.get("features").getAsBoolean();
            builder.putByte("features", (byte) (features ? 1 : 0));

            boolean lakes = root.has("lakes") && root.get("lakes").getAsBoolean();
            builder.putByte("lakes", (byte) (lakes ? 1 : 0));

            return builder.build();
        } catch (Exception e) {
            return CompoundBinaryTag.empty();
        }
    }

    private static boolean getBooleanTag(@Nullable BinaryTag tag, boolean def) {
        if (tag instanceof ByteBinaryTag b) return b.value() != 0;
        if (tag instanceof IntBinaryTag i) return i.value() != 0;
        if (tag instanceof ShortBinaryTag s) return s.value() != 0;
        if (tag instanceof StringBinaryTag str) return Boolean.parseBoolean(str.value()) || "1".equals(str.value());
        return def;
    }

    public static @Nullable String parseLegacyFlatString(@Nullable String raw) {
        if (raw == null || raw.isBlank()) return null;
        try {
            String[] parts = raw.split(";");
            if (parts.length < 2) return null;

            String layersPart = parts.length >= 3 ? parts[1] : parts[0];
            String biomePart = parts.length >= 3 ? parts[2] : (parts.length >= 2 ? parts[1] : "minecraft:plains");

            JsonObject root = new JsonObject();
            JsonArray layers = new JsonArray();

            for (String layerStr : layersPart.split(",")) {
                if (layerStr.isBlank()) continue;
                int count = 1;
                String blockId = layerStr;
                if (layerStr.contains("*")) {
                    String[] countBlock = layerStr.split("\\*", 2);
                    count = Integer.parseInt(countBlock[0]);
                    blockId = countBlock[1];
                }
                if (!blockId.contains(":")) blockId = "minecraft:" + blockId;
                JsonObject l = new JsonObject();
                l.addProperty("block", blockId);
                l.addProperty("height", count);
                layers.add(l);
            }

            root.add("layers", layers);
            String biome = biomePart.contains(":") ? biomePart : "minecraft:" + biomePart;
            root.addProperty("biome", biome);
            root.addProperty("features", false);
            root.addProperty("lakes", false);
            return root.toString();
        } catch (Exception e) {
            return null;
        }
    }

    public void writeDimensionGenFiles(
            @NotNull Path dimensionDir,
            @NotNull NamespacedKey worldKey,
            @NotNull DimensionGenInfo genInfo,
            @NotNull ParsedWorldData parsedData
    ) {
        try {
            Path dataMinecraftDir = dimensionDir.resolve("data").resolve("minecraft");
            Files.createDirectories(dataMinecraftDir);
            Path wgsFile = dataMinecraftDir.resolve("world_gen_settings.dat");

            boolean isVoid = (genInfo.worldType() == WorldType.FLAT
                    && VoyageAPI.isVoidSettings(genInfo.generatorSettingsJson()))
                    || VoyageAPI.isVoidGenerator(genInfo.generatorPluginName());

            CompoundBinaryTag.Builder genTag = CompoundBinaryTag.builder();
            if (isVoid) {
                genTag.putString("type", "minecraft:flat");
                genTag.put("settings", flatJsonToNbt(VoyageAPI.VANILLA_VOID_SETTINGS));
            } else if (genInfo.worldType() == WorldType.FLAT) {
                genTag.putString("type", "minecraft:flat");
                CompoundBinaryTag settings = genInfo.generatorSettingsJson() != null
                        ? flatJsonToNbt(genInfo.generatorSettingsJson())
                        : CompoundBinaryTag.empty();
                genTag.put("settings", settings);
            } else if (genInfo.generatorPluginName() != null) {
                genTag.putString("type", genInfo.generatorPluginName());
            } else {
                genTag.putString("type", "minecraft:noise");
                String noiseSettings = switch (genInfo.environment()) {
                    case NETHER -> "minecraft:nether";
                    case THE_END -> "minecraft:end";
                    default -> genInfo.worldType() == WorldType.AMPLIFIED ? "minecraft:amplified" :
                            (genInfo.worldType() == WorldType.LARGE_BIOMES ? "minecraft:large_biomes" : "minecraft:overworld");
                };
                genTag.putString("settings", noiseSettings);

                CompoundBinaryTag.Builder biomeSource = CompoundBinaryTag.builder();
                if (genInfo.environment() == World.Environment.NETHER) {
                    biomeSource.putString("type", "minecraft:multi_noise");
                    biomeSource.putString("preset", "minecraft:nether");
                } else if (genInfo.environment() == World.Environment.THE_END) {
                    biomeSource.putString("type", "minecraft:the_end");
                } else {
                    biomeSource.putString("type", "minecraft:multi_noise");
                    biomeSource.putString("preset", "minecraft:overworld");
                }
                genTag.put("biome_source", biomeSource.build());
            }

            String dimType = switch (genInfo.environment()) {
                case NETHER -> "minecraft:the_nether";
                case THE_END -> "minecraft:the_end";
                default -> "minecraft:overworld";
            };

            CompoundBinaryTag dimTag = CompoundBinaryTag.builder()
                    .putString("type", dimType)
                    .put("generator", genTag.build())
                    .build();

            CompoundBinaryTag dimsTag = CompoundBinaryTag.builder()
                    .put(worldKey.asString(), dimTag)
                    .put(dimType, dimTag)
                    .build();

            CompoundBinaryTag dataTag = CompoundBinaryTag.builder()
                    .putLong("seed", genInfo.seed())
                    .putByte("generate_structures", (byte) (!isVoid && genInfo.generateStructures() ? 1 : 0))
                    .putByte("bonus_chest", (byte) 0)
                    .put("dimensions", dimsTag)
                    .build();

            int dataVersion = parsedData.dataVersion() > 0 ? parsedData.dataVersion() : LevelDatNbt.serverDataVersion();

            CompoundBinaryTag wgsRoot = CompoundBinaryTag.builder()
                    .put("data", dataTag)
                    .putInt("DataVersion", dataVersion)
                    .build();

            BinaryTagIO.writer().write(wgsRoot, wgsFile, BinaryTagIO.Compression.GZIP);

            Path levelDatFile = dimensionDir.resolve("level.dat");
            CompoundBinaryTag baseData = parsedData.rawLevelData() != null
                    ? parsedData.rawLevelData()
                    : CompoundBinaryTag.empty();
            CompoundBinaryTag.Builder levelDataBuilder = CompoundBinaryTag.builder().put(baseData);

            CompoundBinaryTag worldGenSettingsNbt = CompoundBinaryTag.builder()
                    .putLong("seed", genInfo.seed())
                    .putByte("generate_features", (byte) (!isVoid && genInfo.generateStructures() ? 1 : 0))
                    .putByte("generate_structures", (byte) (!isVoid && genInfo.generateStructures() ? 1 : 0))
                    .putByte("bonus_chest", (byte) 0)
                    .put("dimensions", dimsTag)
                    .build();

            if (isVoid) {
                levelDataBuilder.putString("generatorName", "flat");
                levelDataBuilder.putString("generatorOptions", "minecraft:air;minecraft:the_void");
            } else if (genInfo.worldType() == WorldType.FLAT) {
                levelDataBuilder.putString("generatorName", "flat");
            }
            if (!baseData.keySet().contains("version")) {
                levelDataBuilder.putInt("version", 19133);
            }
            if (!baseData.keySet().contains("LevelName")) {
                levelDataBuilder.putString("LevelName", worldKey.value());
            }
            if (!baseData.keySet().contains("GameType")) {
                levelDataBuilder.putInt("GameType", 1);
            }

            levelDataBuilder.put("WorldGenSettings", worldGenSettingsNbt);
            levelDataBuilder.putLong("RandomSeed", genInfo.seed());
            levelDataBuilder.putInt("DataVersion", dataVersion);

            CompoundBinaryTag levelRoot = CompoundBinaryTag.builder()
                    .put("Data", levelDataBuilder.build())
                    .putInt("DataVersion", dataVersion)
                    .build();

            LevelDatNbt.writeVanillaSafe(levelDatFile, levelRoot, null);

        } catch (Exception e) {
            plugin.getLogger().warning("Failed to persist dimension generator files in " + dimensionDir + ": " + e.getMessage());
        }
    }

    private CompoundBinaryTag extractBorderData(Path legacyDir) {
        Path borderDat = legacyDir.resolve("data").resolve("minecraft").resolve("world_border.dat");
        if (!Files.isRegularFile(borderDat)) {
            borderDat = legacyDir.resolve("data").resolve("world_border.dat");
        }
        if (!Files.isRegularFile(borderDat)) return null;
        try {
            CompoundBinaryTag rawBorder = LevelDatNbt.read(borderDat);
            return rawBorder.keySet().contains("data") ? rawBorder.getCompound("data") : rawBorder;
        } catch (IOException e) {
            plugin.getLogger().warning("Failed to parse world_border.dat.");
            return null;
        }
    }

    public String nbtToJson(@NotNull BinaryTag tag) {
        return switch (tag) {
            case CompoundBinaryTag compound -> {
                StringJoiner sj = new StringJoiner(",", "{", "}");
                for (Map.Entry<String, ? extends BinaryTag> entry : compound) {
                    sj.add("\"" + escapeJsonString(entry.getKey()) + "\":" + nbtToJson(entry.getValue()));
                }
                yield sj.toString();
            }
            case ListBinaryTag list -> {
                StringJoiner sj = new StringJoiner(",", "[", "]");
                for (BinaryTag t : list) {
                    sj.add(nbtToJson(t));
                }
                yield sj.toString();
            }
            case StringBinaryTag str -> "\"" + escapeJsonString(str.value()) + "\"";
            case ByteBinaryTag b -> b.value() == 1 ? "true" : (b.value() == 0 ? "false" : String.valueOf(b.value()));
            case ShortBinaryTag s -> String.valueOf(s.value());
            case IntBinaryTag i -> String.valueOf(i.value());
            case LongBinaryTag l -> String.valueOf(l.value());
            case FloatBinaryTag f -> String.valueOf(f.value());
            case DoubleBinaryTag d -> String.valueOf(d.value());
            case ByteArrayBinaryTag b -> {
                StringJoiner sj = new StringJoiner(",", "[", "]");
                for (byte v : b.value()) sj.add(String.valueOf(v));
                yield sj.toString();
            }
            case IntArrayBinaryTag i -> {
                StringJoiner sj = new StringJoiner(",", "[", "]");
                for (int v : i.value()) sj.add(String.valueOf(v));
                yield sj.toString();
            }
            case LongArrayBinaryTag l -> {
                StringJoiner sj = new StringJoiner(",", "[", "]");
                for (long v : l.value()) sj.add(String.valueOf(v));
                yield sj.toString();
            }
            default -> "null";
        };
    }

    private static String escapeJsonString(String s) {
        return s.replace("\\", "\\\\").replace("\"", "\\\"")
                .replace("\n", "\\n").replace("\r", "\\r").replace("\t", "\\t");
    }

    public void copyDirectoryRecursively(Path source, Path target) throws IOException {
        legacyMigrator.copyDirectoryRecursively(source, target);
    }

    private CompletableFuture<ManagedWorld> createAndRestoreWorld(
            NamespacedKey key,
            DimensionGenInfo genInfo,
            @Nullable CompoundBinaryTag levelData,
            @Nullable CompoundBinaryTag borderData,
            boolean autoLoad
    ) {
        boolean isVoid = (genInfo.worldType() == WorldType.FLAT && VoyageAPI.isVoidSettings(genInfo.generatorSettingsJson()))
                || VoyageAPI.isVoidGenerator(genInfo.generatorPluginName());

        var builder = manager.builder(key)
                .importExisting(true)
                .autoLoad(autoLoad)
                .seed(genInfo.seed())
                .environment(genInfo.environment())
                .type(isVoid ? WorldType.FLAT : genInfo.worldType())
                .generateStructures(!isVoid && genInfo.generateStructures());

        if (isVoid) {
            builder.generatorSettings(VoyageAPI.VANILLA_VOID_SETTINGS);
            builder.generator(manager.getPlugin().getName() + ":void");
        } else {
            if (genInfo.generatorSettingsJson() != null) {
                builder.generatorSettings(genInfo.generatorSettingsJson());
            }
            if (genInfo.generatorPluginName() != null) {
                builder.generator(genInfo.generatorPluginName());
            }
        }

        return builder.create()
                .thenCompose(mw -> mw.isLoaded() ? CompletableFuture.completedFuture(mw) : mw.load().thenApply(w -> mw))
                .thenApply(mw -> {
                    mw.getBukkitWorld().ifPresent(world -> {
                        try {
                            applyLegacyDataToWorld((ManagedWorldImpl) mw, world, levelData, borderData);
                        } catch (Exception e) {
                            plugin.getLogger().warning("Partial failure applying legacy data to " + key.asString() + ": " + e.getMessage());
                        }
                    });
                    return mw;
                });
    }

    private void applyLegacyDataToWorld(ManagedWorldImpl mw, World world, @Nullable CompoundBinaryTag levelData, @Nullable CompoundBinaryTag borderData) {
        if (borderData != null) {
            world.getWorldBorder().setCenter(borderData.getDouble("center_x", 0.0), borderData.getDouble("center_z", 0.0));
            double borderSize = Math.clamp(borderData.getDouble("size", 60000000.0), 1.0, 5.9999968E7);
            world.getWorldBorder().setSize(borderSize);
            world.getWorldBorder().setDamageAmount(borderData.getDouble("damage_per_block", 0.2));
            world.getWorldBorder().setDamageBuffer(borderData.getDouble("safe_zone", 5.0));
            world.getWorldBorder().setWarningDistance(borderData.getInt("warning_blocks", 5));
            world.getWorldBorder().setWarningTimeTicks(borderData.getInt("warning_time", 15) * 20);
        } else if (levelData != null) {
            world.getWorldBorder().setCenter(
                    getDoubleFallback(levelData, 0.0, "BorderCenterX", "border_center_x"),
                    getDoubleFallback(levelData, 0.0, "BorderCenterZ", "border_center_z")
            );
            double levelSize = getDoubleFallback(levelData, 60000000.0, "BorderSize", "border_size");
            world.getWorldBorder().setSize(Math.clamp(levelSize, 1.0, 5.9999968E7));
            world.getWorldBorder().setWarningDistance(getIntFallback(levelData, 5, "BorderWarningBlocks", "border_warning_blocks"));
            world.getWorldBorder().setWarningTimeTicks(getIntFallback(levelData, 15, "BorderWarningTime", "border_warning_time") * 20);
        }

        if (levelData != null) {
            applySpawnLocation(mw, world, levelData);
            world.setTime(getLongFallback(levelData, 0L, "DayTime", "day_time"));

            int diff = getIntFallback(levelData, 2, "Difficulty", "difficulty");
            Difficulty difficulty = switch (diff) {
                case 0 -> Difficulty.PEACEFUL;
                case 1 -> Difficulty.EASY;
                case 3 -> Difficulty.HARD;
                default -> Difficulty.NORMAL;
            };
            world.setDifficulty(difficulty);
            mw.setDifficultyInternal(difficulty);

            CompoundBinaryTag gameRules = getCompoundFallback(levelData, "GameRules", "game_rules");
            if (gameRules != null) applyGameRules(mw, world, gameRules);
        }

        world.save();
        manager.saveState();
    }

    private void applySpawnLocation(ManagedWorldImpl mw, World world, CompoundBinaryTag levelData) {
        if (levelData.keySet().contains("SpawnX")) {
            int x = levelData.getInt("SpawnX", 0);
            int y = levelData.getInt("SpawnY", 0);
            int z = levelData.getInt("SpawnZ", 0);
            world.setSpawnLocation(x, y, z);
            mw.setSpawnInternal(new int[]{x, y, z});
            return;
        }

        CompoundBinaryTag spawnCompound = levelData.getCompound("spawn");
        int[] spawnData = spawnCompound.keySet().contains("pos") ? spawnCompound.getIntArray("pos") : levelData.getIntArray("spawn");

        if (spawnData.length >= 3) {
            world.setSpawnLocation(spawnData[0], spawnData[1], spawnData[2]);
            mw.setSpawnInternal(new int[]{spawnData[0], spawnData[1], spawnData[2]});
        }
    }

    @SuppressWarnings("unchecked")
    private void applyGameRules(ManagedWorldImpl mw, World world, CompoundBinaryTag gameRules) {
        for (Map.Entry<String, ? extends BinaryTag> entry : gameRules) {
            String ruleName = entry.getKey();
            String snakeCaseRule = ruleName.replaceAll("([a-z])([A-Z]+)", "$1_$2").toLowerCase(Locale.ROOT);
            String canonicalRule = normalizeLegacyGameRule(snakeCaseRule);
            NamespacedKey ruleKey = NamespacedKey.fromString(canonicalRule);

            if (ruleKey == null) continue;

            GameRule<?> rule = Registry.GAME_RULE.get(ruleKey);
            if (rule != null) {
                BinaryTag tag = entry.getValue();
                if (rule.getType() == Boolean.class) {
                    boolean parsed = parseBooleanTag(tag);
                    world.setGameRule((GameRule<Boolean>) rule, parsed);
                    mw.setGameRuleInternal((GameRule<Object>) rule, parsed);
                } else if (rule.getType() == Integer.class) {
                    Integer parsed = parseIntegerTag(tag);
                    if (parsed != null) {
                        world.setGameRule((GameRule<Integer>) rule, parsed);
                        mw.setGameRuleInternal((GameRule<Object>) rule, parsed);
                    }
                }
            }
        }
    }

    private static String normalizeLegacyGameRule(@NotNull String rule) {
        return switch (rule) {
            case "do_daylight_cycle", "dodaylightcycle" -> "advance_time";
            case "do_weather_cycle", "doweathercycle" -> "advance_weather";
            case "do_mob_spawning", "domobspawning" -> "spawn_mobs";
            case "do_tile_drops", "dotiledrops" -> "block_drops";
            case "do_entity_drops", "doentitydrops" -> "entity_drops";
            case "do_immediate_respawn", "doimmediaterespawn" -> "immediate_respawn";
            case "do_insomnia", "doinsomnia" -> "spawn_phantoms";
            case "do_patrol_spawning", "dopatrolspawning" -> "spawn_patrols";
            case "do_trader_spawning", "dotraderspawning" -> "spawn_wandering_traders";
            case "do_vines_spread", "dovinesspread" -> "spread_vines";
            case "do_warden_spawning", "dowardenspawning" -> "spawn_wardens";
            default -> rule;
        };
    }

    private static boolean parseBooleanTag(@NotNull BinaryTag tag) {
        if (tag instanceof ByteBinaryTag b) return b.value() != 0;
        if (tag instanceof IntBinaryTag i) return i.value() != 0;
        if (tag instanceof ShortBinaryTag s) return s.value() != 0;
        if (tag instanceof StringBinaryTag str) return Boolean.parseBoolean(str.value()) || "1".equals(str.value());
        return false;
    }

    private static @Nullable Integer parseIntegerTag(@NotNull BinaryTag tag) {
        if (tag instanceof IntBinaryTag i) return i.value();
        if (tag instanceof ByteBinaryTag b) return (int) b.value();
        if (tag instanceof ShortBinaryTag s) return (int) s.value();
        if (tag instanceof StringBinaryTag str) {
            try { return Integer.parseInt(str.value()); } catch (NumberFormatException ignored) {}
        }
        return null;
    }

    private double getDoubleFallback(CompoundBinaryTag tag, double def, String... keys) {
        for (String k : keys) if (tag.keySet().contains(k)) return tag.getDouble(k);
        return def;
    }

    private int getIntFallback(CompoundBinaryTag tag, int def, String... keys) {
        for (String k : keys) if (tag.keySet().contains(k)) return tag.getInt(k);
        return def;
    }

    private long getLongFallback(CompoundBinaryTag tag, long def, String... keys) {
        for (String k : keys) if (tag.keySet().contains(k)) return tag.getLong(k);
        return def;
    }

    private CompoundBinaryTag getCompoundFallback(CompoundBinaryTag tag, String... keys) {
        for (String k : keys) if (tag.keySet().contains(k)) return tag.getCompound(k);
        return null;
    }
}