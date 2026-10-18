package org.libregalaxy.voyage.core.migration;

import net.kyori.adventure.nbt.BinaryTag;
import net.kyori.adventure.nbt.BinaryTagIO;
import net.kyori.adventure.nbt.BinaryTagTypes;
import net.kyori.adventure.nbt.CompoundBinaryTag;
import net.kyori.adventure.nbt.IntArrayBinaryTag;
import net.kyori.adventure.nbt.ListBinaryTag;
import net.kyori.adventure.nbt.StringBinaryTag;
import org.bukkit.Bukkit;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.nio.file.FileVisitResult;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.SimpleFileVisitor;
import java.nio.file.StandardOpenOption;
import java.nio.file.attribute.BasicFileAttributes;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.logging.Logger;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

public final class VanillaWorldExporter {

    private static final String LEVEL_DAT = "level.dat";
    private static final String WGS_FILE = "data/minecraft/world_gen_settings.dat";
    private static final String WORLD_BORDER_FILE = "data/minecraft/world_border.dat";
    private static final String LEGACY_WORLD_BORDER_FILE = "data/world_border.dat";
    private static final String PLAYER_DATA_DIR = "playerdata";
    private static final String MODERN_PLAYERS_DATA_DIR = "players/data";
    private static final String OVERWORLD_DIM_PREFIX = "dimensions/minecraft/overworld/";
    private static final String VANILLA_OVERWORLD_KEY = "minecraft:overworld";

    /** Server/multiplayer files with no meaning in a vanilla singleplayer save.
     *  The raw on-disk world_gen_settings.dat and world_border.dat are skipped so our sanitized versions replace them. */
    private static final Set<String> SKIP_FILES = Set.of(
            "uid.dat", "session.lock", "level.dat_old", "paper-world.yml", "world_gen_settings.dat", "world_border.dat"
    );
    private static final Set<String> SKIP_DIRS = Set.of("playerdata", "players", "stats", "advancements", "paper", "dimensions");

    /** Directory trees belonging to the dimension payload that 26.x places under dimensions/minecraft/overworld/. */
    private static final Set<String> DIMENSION_PAYLOAD_DIRS = Set.of("region", "entities", "poi");

    public record WorldBorderData(
            double centerX, double centerZ, double size,
            double damagePerBlock, double safeZone,
            int warningBlocks, int warningTime
    ) {}

    public record LiveWorldMetadata(
            int spawnX, int spawnY, int spawnZ, float spawnYaw,
            int difficultyId, long dayTime, int defaultGameType
    ) {}

    private final Logger logger;

    public VanillaWorldExporter(@NotNull Logger logger) {
        this.logger = logger;
    }

    public void export(@NotNull Path worldDir, @NotNull String name, boolean voidWorld,
                       @NotNull Path targetZip) throws IOException {
        export(worldDir, name, null, voidWorld, List.of(), null, null, null, targetZip);
    }

    public void export(@NotNull Path worldDir, @NotNull String folderName, @Nullable String levelName,
                       boolean voidWorld,
                       @NotNull List<VanillaPlayerData.ExportedPlayer> players,
                       @Nullable UUID primaryPlayer,
                       @NotNull Path targetZip) throws IOException {
        export(worldDir, folderName, levelName, voidWorld, players, primaryPlayer, null, null, targetZip);
    }

    public void export(@NotNull Path worldDir, @NotNull String folderName, @Nullable String levelName,
                       boolean voidWorld,
                       @NotNull List<VanillaPlayerData.ExportedPlayer> players,
                       @Nullable UUID primaryPlayer,
                       @Nullable WorldBorderData liveBorder,
                       @NotNull Path targetZip) throws IOException {
        export(worldDir, folderName, levelName, voidWorld, players, primaryPlayer, liveBorder, null, targetZip);
    }

    private static long countMcaFiles(@Nullable Path dir) {
        if (dir == null || !Files.isDirectory(dir)) return 0;
        try (var stream = Files.list(dir)) {
            return stream.filter(p -> p.getFileName().toString().endsWith(".mca")).count();
        } catch (IOException e) {
            return 0;
        }
    }

    public void export(@NotNull Path worldDir, @NotNull String folderName, @Nullable String levelName,
                       boolean voidWorld,
                       @NotNull List<VanillaPlayerData.ExportedPlayer> players,
                       @Nullable UUID primaryPlayer,
                       @Nullable WorldBorderData liveBorder,
                       @Nullable LiveWorldMetadata liveMetadata,
                       @NotNull Path targetZip) throws IOException {
        if (!Files.isDirectory(worldDir)) {
            throw new IOException("World directory does not exist: " + worldDir);
        }
        Path sourceLevelDat = worldDir.resolve(LEVEL_DAT);
        if (!Files.isRegularFile(sourceLevelDat)) {
            throw new IOException("Cannot export " + worldDir + ": level.dat is missing");
        }

        // Region verification & fallback discovery
        Path regionDir = worldDir.resolve("region");
        long mcaCount = countMcaFiles(regionDir);
        Path actualRegionSource = regionDir;

        if (mcaCount == 0) {
            logger.warning("No .mca files in " + regionDir.toAbsolutePath() + "! Searching fallback locations...");
            List<Path> fallbacks = List.of(
                    worldDir.resolve("dimensions").resolve("minecraft").resolve("overworld").resolve("region"),
                    Bukkit.getServer().getLevelDirectory().resolve("dimensions").resolve(folderName.replace('_', '/')).resolve("region"),
                    Bukkit.getWorldContainer().toPath().resolve("dimensions").resolve(folderName.replace('_', '/')).resolve("region"),
                    Bukkit.getWorldContainer().toPath().resolve(folderName).resolve("region"),
                    Bukkit.getWorldContainer().toPath().resolve(folderName.replace("planet_", "")).resolve("region")
            );

            for (Path fb : fallbacks) {
                long fbCount = countMcaFiles(fb);
                if (fbCount > 0) {
                    logger.info("Found " + fbCount + " region file(s) in fallback location: " + fb.toAbsolutePath());
                    actualRegionSource = fb;
                    mcaCount = fbCount;
                    break;
                }
            }
        }

        if (mcaCount > 0) {
            logger.info("Exporting world from: " + worldDir.toAbsolutePath() + " (packaging " + mcaCount + " region .mca file(s) from " + actualRegionSource.toAbsolutePath() + ")");
        } else {
            logger.severe("CRITICAL WARNING: Zero .mca region files found for " + folderName + "! The exported world will contain no blocks!");
        }

        String effectiveLevelName = levelName != null && !levelName.isBlank() ? levelName : folderName;

        Path parent = targetZip.toAbsolutePath().getParent();
        if (parent != null) Files.createDirectories(parent);

        Path tempLevelDat = Files.createTempFile("voyage-level-dat", ".dat");
        Path tempWgsDat = Files.createTempFile("voyage-wgs-dat", ".dat");
        Path tempBorderDat = null;
        Map<UUID, Path> tempPlayerData = new LinkedHashMap<>();
        Set<String> addedEntries = new HashSet<>();

        try {
            // 1. Build the vanilla-safe level.dat, embedding the primary player.
            CompoundBinaryTag root = LevelDatNbt.read(sourceLevelDat);
            root = mergeWorldGenSettings(root, worldDir);
            root = stripPlayerTag(root);
            if (voidWorld) {
                root = applyVanillaVoidGenerator(root);
            } else {
                root = sanitizeDimensionsForVanilla(root);
            }

            // Ensure standard vanilla dimensions exist so the codec validates
            root = ensureAllVanillaDimensions(root);

            // Stamp live world metadata (spawn, difficulty, time, gametype) if provided
            if (liveMetadata != null) {
                root = applyLiveMetadata(root, liveMetadata);
            }

            // Normalize spawn tags so both 1.21.x (SpawnX/Y/Z) and 26.x (spawn compound) point to minecraft:overworld
            root = normalizeVanillaSpawn(root);

            // Sanitize DataPacks tag to strictly ["vanilla"], stripping server-side "paper" or "bukkit"
            root = sanitizeDataPacks(root);

            // World border: prefer liveBorder if provided, otherwise mirror whatever level.dat
            // (or world_border.dat) carries into the flat Data tags 26.x actually reads.
            WorldBorderData border = liveBorder != null ? liveBorder : resolveWorldBorder(root, worldDir);
            if (border != null) {
                root = applyWorldBorder(root, border);
            }

            // Singleplayer quality of life: "Allow Cheats" on.
            root = enableCheats(root);

            int serverDataVersion = LevelDatNbt.serverDataVersion();

            // Ensure modern DataVersion, Version compound, and remove legacy conversion flags
            root = LevelDatNbt.ensureVanillaLevelData(root, effectiveLevelName, serverDataVersion);
            int dataVersion = LevelDatNbt.resolveDataVersion(root, serverDataVersion);

            // Because singleplayer mounts the primary exported dimension as minecraft:overworld,
            // players must be placed in minecraft:overworld (not a custom "planet:..." namespace).
            String dimensionKey = VANILLA_OVERWORLD_KEY;
            double[] spawnFallback = readSpawn(root);

            Map<UUID, CompoundBinaryTag> normalizedPlayers = new LinkedHashMap<>();
            for (VanillaPlayerData.ExportedPlayer player : players) {
                normalizedPlayers.put(player.uuid(), VanillaPlayerData.normalizeForVanilla(
                        player, dimensionKey, spawnFallback, dataVersion));
            }

            // If primaryPlayer was requested but had no stored state in this world yet,
            // synthesize a clean player entry at the world spawn so singleplayer places them at the build.
            if (primaryPlayer != null && !normalizedPlayers.containsKey(primaryPlayer)) {
                double[] sp = spawnFallback != null ? spawnFallback : new double[]{0.5, 64.0, 0.5};
                float yaw = liveMetadata != null ? liveMetadata.spawnYaw() : 0.0f;
                int gm = liveMetadata != null ? liveMetadata.defaultGameType() : 1;
                CompoundBinaryTag syntheticBase = CompoundBinaryTag.builder()
                        .putInt("playerGameType", gm)
                        .build();
                VanillaPlayerData.ExportedPlayer syntheticPlayer = new VanillaPlayerData.ExportedPlayer(
                        primaryPlayer,
                        null,
                        syntheticBase,
                        new VanillaPlayerData.LastPosition(sp[0], sp[1], sp[2], yaw, 0.0f)
                );
                normalizedPlayers.put(primaryPlayer, VanillaPlayerData.normalizeForVanilla(
                        syntheticPlayer, dimensionKey, sp, dataVersion));
            }

            UUID primary = (primaryPlayer != null && normalizedPlayers.containsKey(primaryPlayer))
                    ? primaryPlayer
                    : (!normalizedPlayers.isEmpty() ? normalizedPlayers.keySet().iterator().next() : null);

            if (primary != null) {
                CompoundBinaryTag data = LevelDatNbt.getData(root);
                CompoundBinaryTag.Builder dataBuilder = CompoundBinaryTag.builder().put(data);
                dataBuilder.put("Player", normalizedPlayers.get(primary));
                CompoundBinaryTag.Builder rootBuilder = CompoundBinaryTag.builder().put(root);
                rootBuilder.put(LevelDatNbt.DATA_KEY, dataBuilder.build());
                root = rootBuilder.build();
            }

            LevelDatNbt.writeVanillaSafe(tempLevelDat, root, effectiveLevelName);

            // 2. Synthesize matching data/minecraft/world_gen_settings.dat required by Minecraft 26.x
            CompoundBinaryTag exportedData = LevelDatNbt.getData(root);
            CompoundBinaryTag exportedWgs = exportedData.getCompound("WorldGenSettings");
            CompoundBinaryTag wgsRoot = CompoundBinaryTag.builder()
                    .put("data", exportedWgs)
                    .putInt("DataVersion", dataVersion)
                    .build();
            BinaryTagIO.writer().write(wgsRoot, tempWgsDat, BinaryTagIO.Compression.GZIP);

            // Synthesize data/minecraft/world_border.dat if border is present
            if (border != null) {
                tempBorderDat = Files.createTempFile("voyage-border-dat", ".dat");
                double clampedSize = Math.clamp(border.size(), 1.0, 5.9999968E7);
                CompoundBinaryTag borderDataTag = CompoundBinaryTag.builder()
                        .putDouble("center_x", border.centerX())
                        .putDouble("center_z", border.centerZ())
                        .putDouble("size", clampedSize)
                        .putDouble("lerp_target", clampedSize)
                        .putLong("lerp_time", 0L)
                        .putDouble("damage_per_block", border.damagePerBlock())
                        .putDouble("safe_zone", border.safeZone())
                        .putInt("warning_blocks", border.warningBlocks())
                        .putInt("warning_time", border.warningTime())
                        .build();
                CompoundBinaryTag borderRoot = CompoundBinaryTag.builder()
                        .put("data", borderDataTag)
                        .putInt("DataVersion", dataVersion)
                        .build();
                BinaryTagIO.writer().write(borderRoot, tempBorderDat, BinaryTagIO.Compression.GZIP);
            }

            // 3. Build + verify each playerdata/<uuid>.dat.
            for (Map.Entry<UUID, CompoundBinaryTag> entry : normalizedPlayers.entrySet()) {
                Path temp = Files.createTempFile("voyage-playerdata-", ".dat");
                LevelDatNbt.write(temp, entry.getValue());
                if (LevelDatNbt.readDataVersion(temp) <= 0) {
                    throw new IOException("Exported player data for " + entry.getKey()
                            + " failed verification (DataVersion <= 0)");
                }
                tempPlayerData.put(entry.getKey(), temp);
            }

            // 4. Zip everything under a named world folder without duplicate entries.
            String base = sanitizeFolderName(folderName) + "/";
            String overworldBase = base + OVERWORLD_DIM_PREFIX;

            try (ZipOutputStream zip = new ZipOutputStream(Files.newOutputStream(
                    targetZip, StandardOpenOption.CREATE, StandardOpenOption.TRUNCATE_EXISTING))) {

                putDirEntry(zip, base, addedEntries);
                addFileEntry(zip, base + LEVEL_DAT, tempLevelDat, addedEntries);

                // Ensure 26.x dimensions/minecraft/overworld/ hierarchy exists in the archive
                putDirEntry(zip, base + "dimensions/", addedEntries);
                putDirEntry(zip, base + "dimensions/minecraft/", addedEntries);
                putDirEntry(zip, overworldBase, addedEntries);

                // Add the sanitized world_gen_settings.dat companion file
                putDirEntry(zip, base + "data/", addedEntries);
                putDirEntry(zip, base + "data/minecraft/", addedEntries);
                addFileEntry(zip, base + WGS_FILE, tempWgsDat, addedEntries);

                if (tempBorderDat != null) {
                    addFileEntry(zip, base + WORLD_BORDER_FILE, tempBorderDat, addedEntries);
                }

                // If fallback region directory was required, explicitly copy .mca files into 26.x overworld paths
                if (!actualRegionSource.equals(regionDir) && Files.isDirectory(actualRegionSource)) {
                    putDirEntry(zip, overworldBase + "region/", addedEntries);
                    try (var stream = Files.list(actualRegionSource)) {
                        for (Path mca : stream.filter(p -> p.getFileName().toString().endsWith(".mca")).toList()) {
                            String mcaName = mca.getFileName().toString();
                            addFileEntry(zip, overworldBase + "region/" + mcaName, mca, addedEntries);
                            logger.info("  Zipped fallback region file: " + overworldBase + "region/" + mcaName + " (" + Files.size(mca) + " bytes)");
                        }
                    }
                }

                // Converted Voyage player data — written to both 26.x (players/data/) and legacy (playerdata/) locations.
                if (!tempPlayerData.isEmpty()) {
                    putDirEntry(zip, base + PLAYER_DATA_DIR + "/", addedEntries);
                    putDirEntry(zip, base + "players/", addedEntries);
                    putDirEntry(zip, base + MODERN_PLAYERS_DATA_DIR + "/", addedEntries);
                    for (Map.Entry<UUID, Path> entry : tempPlayerData.entrySet()) {
                        String datFile = entry.getKey() + ".dat";
                        addFileEntry(zip, base + PLAYER_DATA_DIR + "/" + datFile, entry.getValue(), addedEntries);
                        addFileEntry(zip, base + MODERN_PLAYERS_DATA_DIR + "/" + datFile, entry.getValue(), addedEntries);
                    }
                }

                zipWorldTree(worldDir, base, overworldBase, zip, addedEntries);
            }
        } catch (UncheckedIOException unchecked) {
            safeDelete(targetZip);
            throw unchecked.getCause();
        } catch (IOException io) {
            safeDelete(targetZip);
            throw io;
        } finally {
            try {
                Files.deleteIfExists(tempLevelDat);
            } catch (IOException ignored) {}
            try {
                Files.deleteIfExists(tempWgsDat);
            } catch (IOException ignored) {}
            if (tempBorderDat != null) {
                try {
                    Files.deleteIfExists(tempBorderDat);
                } catch (IOException ignored) {}
            }
            for (Path temp : tempPlayerData.values()) {
                try {
                    Files.deleteIfExists(temp);
                } catch (IOException ignored) {}
            }
        }
    }

    private static @NotNull CompoundBinaryTag applyLiveMetadata(@NotNull CompoundBinaryTag root,
                                                                @NotNull LiveWorldMetadata meta) {
        CompoundBinaryTag data = LevelDatNbt.getData(root);
        CompoundBinaryTag.Builder dataBuilder = CompoundBinaryTag.builder().put(data);

        dataBuilder.putInt("SpawnX", meta.spawnX());
        dataBuilder.putInt("SpawnY", meta.spawnY());
        dataBuilder.putInt("SpawnZ", meta.spawnZ());
        dataBuilder.putFloat("SpawnAngle", meta.spawnYaw());

        CompoundBinaryTag.Builder spawnBuilder = CompoundBinaryTag.builder();
        if (data.keySet().contains("spawn") && data.get("spawn") instanceof CompoundBinaryTag existingSpawn) {
            spawnBuilder.put(existingSpawn);
        }
        spawnBuilder.putIntArray("pos", new int[]{meta.spawnX(), meta.spawnY(), meta.spawnZ()});
        spawnBuilder.putString("dimension", VANILLA_OVERWORLD_KEY);
        spawnBuilder.putFloat("angle", meta.spawnYaw());
        dataBuilder.put("spawn", spawnBuilder.build());

        dataBuilder.putByte("Difficulty", (byte) meta.difficultyId());
        dataBuilder.putLong("DayTime", meta.dayTime());
        dataBuilder.putInt("GameType", meta.defaultGameType());

        CompoundBinaryTag.Builder rootBuilder = CompoundBinaryTag.builder().put(root);
        rootBuilder.put(LevelDatNbt.DATA_KEY, dataBuilder.build());
        return rootBuilder.build();
    }

    private static @NotNull CompoundBinaryTag enableCheats(@NotNull CompoundBinaryTag root) {
        CompoundBinaryTag data = LevelDatNbt.getData(root);
        CompoundBinaryTag.Builder dataBuilder = CompoundBinaryTag.builder().put(data);
        dataBuilder.putByte("allowCommands", (byte) 1);
        dataBuilder.putByte("allow_commands", (byte) 1);
        CompoundBinaryTag.Builder rootBuilder = CompoundBinaryTag.builder().put(root);
        rootBuilder.put(LevelDatNbt.DATA_KEY, dataBuilder.build());
        return rootBuilder.build();
    }

    static @NotNull CompoundBinaryTag sanitizeDataPacks(@NotNull CompoundBinaryTag root) {
        CompoundBinaryTag data = LevelDatNbt.getData(root);
        CompoundBinaryTag.Builder dataBuilder = CompoundBinaryTag.builder().put(data);

        ListBinaryTag.Builder<StringBinaryTag> enabledPacks = ListBinaryTag.builder(BinaryTagTypes.STRING);
        enabledPacks.add(StringBinaryTag.stringBinaryTag("vanilla"));

        dataBuilder.put("DataPacks", CompoundBinaryTag.builder()
                .put("Enabled", enabledPacks.build())
                .put("Disabled", ListBinaryTag.builder(BinaryTagTypes.STRING).build())
                .build());

        ListBinaryTag.Builder<StringBinaryTag> enabledFeatures = ListBinaryTag.builder(BinaryTagTypes.STRING);
        enabledFeatures.add(StringBinaryTag.stringBinaryTag("minecraft:vanilla"));
        dataBuilder.put("enabled_features", enabledFeatures.build());

        CompoundBinaryTag.Builder rootBuilder = CompoundBinaryTag.builder().put(root);
        rootBuilder.put(LevelDatNbt.DATA_KEY, dataBuilder.build());
        return rootBuilder.build();
    }

    public static @NotNull CompoundBinaryTag applyWorldBorder(@NotNull CompoundBinaryTag root,
                                                              @NotNull WorldBorderData border) {
        double size = Math.clamp(border.size(), 1.0, 5.9999968E7);

        CompoundBinaryTag data = LevelDatNbt.getData(root);
        CompoundBinaryTag.Builder dataBuilder = CompoundBinaryTag.builder().put(data);

        dataBuilder.putDouble("BorderCenterX", border.centerX());
        dataBuilder.putDouble("BorderCenterZ", border.centerZ());
        dataBuilder.putDouble("BorderSize", size);
        dataBuilder.putDouble("BorderSizeLerpTarget", size);
        dataBuilder.putLong("BorderSizeLerpTime", 0L);
        dataBuilder.putDouble("BorderSafeZone", border.safeZone());
        dataBuilder.putDouble("BorderDamagePerBlock", border.damagePerBlock());
        dataBuilder.putDouble("BorderWarningBlocks", border.warningBlocks());
        dataBuilder.putDouble("BorderWarningTime", border.warningTime());

        dataBuilder.putDouble("border_center_x", border.centerX());
        dataBuilder.putDouble("border_center_z", border.centerZ());
        dataBuilder.putDouble("border_size", size);
        dataBuilder.putDouble("border_size_lerp_target", size);
        dataBuilder.putLong("border_size_lerp_time", 0L);
        dataBuilder.putDouble("border_safe_zone", border.safeZone());
        dataBuilder.putDouble("border_damage_per_block", border.damagePerBlock());
        dataBuilder.putDouble("border_warning_blocks", border.warningBlocks());
        dataBuilder.putDouble("border_warning_time", border.warningTime());

        CompoundBinaryTag.Builder rootBuilder = CompoundBinaryTag.builder().put(root);
        rootBuilder.put(LevelDatNbt.DATA_KEY, dataBuilder.build());
        return rootBuilder.build();
    }

    private static @Nullable WorldBorderData resolveWorldBorder(@NotNull CompoundBinaryTag root,
                                                                @NotNull Path worldDir) {
        CompoundBinaryTag data = LevelDatNbt.getData(root);

        if (data.keySet().contains("BorderSize") || data.keySet().contains("border_size")) {
            return new WorldBorderData(
                    doubleFrom(data, "BorderCenterX", "border_center_x", 0.0),
                    doubleFrom(data, "BorderCenterZ", "border_center_z", 0.0),
                    Math.clamp(doubleFrom(data, "BorderSize", "border_size", 60000000.0), 1.0, 5.9999968E7),
                    doubleFrom(data, "BorderDamagePerBlock", "border_damage_per_block", 0.2),
                    doubleFrom(data, "BorderSafeZone", "border_safe_zone", 5.0),
                    (int) doubleFrom(data, "BorderWarningBlocks", "border_warning_blocks", 5.0),
                    (int) doubleFrom(data, "BorderWarningTime", "border_warning_time", 15.0));
        }

        CompoundBinaryTag source = null;
        if (data.keySet().contains("world_border")) {
            source = data.getCompound("world_border");
        } else if (data.keySet().contains("WorldBorder")) {
            source = data.getCompound("WorldBorder");
        } else {
            Path borderFile = worldDir.resolve(WORLD_BORDER_FILE);
            if (!Files.isRegularFile(borderFile)) {
                borderFile = worldDir.resolve(LEGACY_WORLD_BORDER_FILE);
            }
            if (!Files.isRegularFile(borderFile)) {
                borderFile = worldDir.resolve(OVERWORLD_DIM_PREFIX).resolve(WORLD_BORDER_FILE);
            }
            if (!Files.isRegularFile(borderFile)) {
                borderFile = worldDir.resolve(OVERWORLD_DIM_PREFIX).resolve(LEGACY_WORLD_BORDER_FILE);
            }
            if (Files.isRegularFile(borderFile)) {
                try {
                    CompoundBinaryTag fileRoot = LevelDatNbt.read(borderFile);
                    source = fileRoot.keySet().contains("data") ? fileRoot.getCompound("data") : fileRoot;
                } catch (IOException ignored) {}
            }
        }
        if (source == null) return null;

        return new WorldBorderData(
                doubleFrom(source, "center_x", "BorderCenterX", 0.0),
                doubleFrom(source, "center_z", "BorderCenterZ", 0.0),
                Math.clamp(doubleFrom(source, "size", "BorderSize", 60000000.0), 1.0, 5.9999968E7),
                doubleFrom(source, "damage_per_block", "BorderDamagePerBlock", 0.2),
                doubleFrom(source, "safe_zone", "BorderSafeZone", 5.0),
                intFrom(source, "warning_blocks", "BorderWarningBlocks", 5),
                intFrom(source, "warning_time", "BorderWarningTime", 15));
    }

    private static double doubleFrom(@NotNull CompoundBinaryTag tag, @NotNull String primary,
                                     @NotNull String secondary, double def) {
        if (tag.keySet().contains(primary)) return tag.getDouble(primary, def);
        return tag.getDouble(secondary, def);
    }

    private static int intFrom(@NotNull CompoundBinaryTag tag, @NotNull String primary,
                               @NotNull String secondary, int def) {
        if (tag.keySet().contains(primary)) return tag.getInt(primary, def);
        return tag.getInt(secondary, def);
    }

    private @NotNull CompoundBinaryTag mergeWorldGenSettings(@NotNull CompoundBinaryTag root,
                                                             @NotNull Path worldDir) {
        CompoundBinaryTag data = LevelDatNbt.getData(root);
        Path wgsFile = worldDir.resolve(WGS_FILE);
        if (!Files.isRegularFile(wgsFile)) {
            return root;
        }

        try {
            CompoundBinaryTag wgsRoot = LevelDatNbt.read(wgsFile);
            CompoundBinaryTag wgsData = wgsRoot.keySet().contains("data")
                    ? wgsRoot.getCompound("data") : wgsRoot;
            if (!wgsData.keySet().contains("dimensions") && !wgsData.keySet().contains("seed")) {
                return root;
            }

            int bestVersion = LevelDatNbt.resolveDataVersion(root, 0);
            if (wgsRoot.keySet().contains(LevelDatNbt.DATA_VERSION_KEY)) {
                bestVersion = Math.max(bestVersion, wgsRoot.getInt(LevelDatNbt.DATA_VERSION_KEY, 0));
            }

            // If level.dat lacks WorldGenSettings or its dimensions compound is empty, adopt wgsData
            if (!data.keySet().contains("WorldGenSettings")
                    || !data.getCompound("WorldGenSettings").keySet().contains("dimensions")
                    || data.getCompound("WorldGenSettings").getCompound("dimensions").keySet().isEmpty()) {
                CompoundBinaryTag.Builder dataBuilder = CompoundBinaryTag.builder().put(data);
                dataBuilder.put("WorldGenSettings", wgsData);
                CompoundBinaryTag.Builder rootBuilder = CompoundBinaryTag.builder().put(root);
                rootBuilder.put(LevelDatNbt.DATA_KEY, dataBuilder.build());
                if (bestVersion > 0) {
                    rootBuilder.putInt(LevelDatNbt.DATA_VERSION_KEY, bestVersion);
                }
                return rootBuilder.build();
            }

            return root;
        } catch (IOException e) {
            logger.warning("Could not merge world_gen_settings.dat into the exported level.dat: " + e.getMessage());
            return root;
        }
    }

    private static @NotNull CompoundBinaryTag stripPlayerTag(@NotNull CompoundBinaryTag root) {
        CompoundBinaryTag data = LevelDatNbt.getData(root);
        if (!data.keySet().contains("Player")) {
            return root;
        }
        CompoundBinaryTag.Builder dataBuilder = CompoundBinaryTag.builder();
        for (Map.Entry<String, ? extends BinaryTag> entry : data) {
            if (!entry.getKey().equals("Player")) {
                dataBuilder.put(entry.getKey(), entry.getValue());
            }
        }
        CompoundBinaryTag.Builder rootBuilder = CompoundBinaryTag.builder().put(root);
        rootBuilder.put(LevelDatNbt.DATA_KEY, dataBuilder.build());
        return rootBuilder.build();
    }

    static @NotNull String primaryDimensionKey(@NotNull CompoundBinaryTag root) {
        CompoundBinaryTag data = LevelDatNbt.getData(root);
        if (data.keySet().contains("WorldGenSettings")) {
            CompoundBinaryTag wgs = data.getCompound("WorldGenSettings");
            if (wgs.keySet().contains("dimensions")) {
                CompoundBinaryTag dims = wgs.getCompound("dimensions");
                if (dims.keySet().size() == 1) {
                    return dims.keySet().iterator().next();
                }
                for (String key : dims.keySet()) {
                    if (!key.startsWith("minecraft:")) return key;
                }
                for (String key : dims.keySet()) {
                    if (key.endsWith("overworld")) return key;
                }
                if (!dims.keySet().isEmpty()) {
                    return dims.keySet().iterator().next();
                }
            }
        }
        return VANILLA_OVERWORLD_KEY;
    }

    /**
     * Reads the world spawn coordinates from either legacy SpawnX/SpawnY/SpawnZ
     * or modern 26.x Data.spawn compound / int array.
     */
    private static double[] readSpawn(@NotNull CompoundBinaryTag root) {
        CompoundBinaryTag data = LevelDatNbt.getData(root);
        if (data.keySet().contains("SpawnX") && data.keySet().contains("SpawnY")
                && data.keySet().contains("SpawnZ")) {
            return new double[]{
                    data.getInt("SpawnX", 0) + 0.5,
                    data.getInt("SpawnY", 64),
                    data.getInt("SpawnZ", 0) + 0.5
            };
        }
        if (data.keySet().contains("spawn")) {
            BinaryTag rawSpawn = data.get("spawn");
            if (rawSpawn instanceof CompoundBinaryTag spawnComp && spawnComp.keySet().contains("pos")) {
                int[] pos = spawnComp.getIntArray("pos");
                if (pos.length >= 3) {
                    return new double[]{pos[0] + 0.5, pos[1], pos[2] + 0.5};
                }
            } else if (rawSpawn instanceof IntArrayBinaryTag intArr) {
                int[] pos = intArr.value();
                if (pos.length >= 3) {
                    return new double[]{pos[0] + 0.5, pos[1], pos[2] + 0.5};
                }
            }
        }
        return null;
    }

    /**
     * Ensures that both legacy SpawnX/SpawnY/SpawnZ and modern 26.x Data.spawn
     * compound exist and point to "minecraft:overworld" so singleplayer never
     * tries to spawn the player in a non-existent custom dimension key.
     */
    static @NotNull CompoundBinaryTag normalizeVanillaSpawn(@NotNull CompoundBinaryTag root) {
        CompoundBinaryTag data = LevelDatNbt.getData(root);
        int spawnX = 0;
        int spawnY = 64;
        int spawnZ = 0;
        float angle = data.getFloat("SpawnAngle", 0.0f);
        boolean found = false;

        if (data.keySet().contains("spawn")) {
            BinaryTag rawSpawn = data.get("spawn");
            if (rawSpawn instanceof CompoundBinaryTag spawnComp) {
                int[] pos = spawnComp.getIntArray("pos");
                if (pos.length >= 3) {
                    spawnX = pos[0];
                    spawnY = pos[1];
                    spawnZ = pos[2];
                    found = true;
                }
                if (spawnComp.keySet().contains("angle")) {
                    angle = spawnComp.getFloat("angle", angle);
                } else if (spawnComp.keySet().contains("yaw")) {
                    angle = spawnComp.getFloat("yaw", angle);
                }
            } else if (rawSpawn instanceof IntArrayBinaryTag intArr) {
                int[] pos = intArr.value();
                if (pos.length >= 3) {
                    spawnX = pos[0];
                    spawnY = pos[1];
                    spawnZ = pos[2];
                    found = true;
                }
            }
        }

        if (!found && data.keySet().contains("SpawnX") && data.keySet().contains("SpawnY") && data.keySet().contains("SpawnZ")) {
            spawnX = data.getInt("SpawnX", 0);
            spawnY = data.getInt("SpawnY", 64);
            spawnZ = data.getInt("SpawnZ", 0);
            found = true;
        }

        CompoundBinaryTag.Builder dataBuilder = CompoundBinaryTag.builder().put(data);
        dataBuilder.putInt("SpawnX", spawnX);
        dataBuilder.putInt("SpawnY", spawnY);
        dataBuilder.putInt("SpawnZ", spawnZ);
        dataBuilder.putFloat("SpawnAngle", angle);

        CompoundBinaryTag.Builder spawnBuilder = CompoundBinaryTag.builder();
        if (data.keySet().contains("spawn") && data.get("spawn") instanceof CompoundBinaryTag existingSpawn) {
            spawnBuilder.put(existingSpawn);
        }
        spawnBuilder.putIntArray("pos", new int[]{spawnX, spawnY, spawnZ});
        spawnBuilder.putString("dimension", VANILLA_OVERWORLD_KEY);
        spawnBuilder.putFloat("angle", angle);
        dataBuilder.put("spawn", spawnBuilder.build());

        CompoundBinaryTag.Builder rootBuilder = CompoundBinaryTag.builder().put(root);
        rootBuilder.put(LevelDatNbt.DATA_KEY, dataBuilder.build());
        return rootBuilder.build();
    }

    static @NotNull CompoundBinaryTag ensureAllVanillaDimensions(@NotNull CompoundBinaryTag root) {
        CompoundBinaryTag data = LevelDatNbt.getData(root);
        if (!data.keySet().contains("WorldGenSettings")) return root;

        CompoundBinaryTag wgs = data.getCompound("WorldGenSettings");
        if (!wgs.keySet().contains("dimensions")) return root;

        CompoundBinaryTag dims = wgs.getCompound("dimensions");
        boolean hasNether = dims.keySet().contains("minecraft:the_nether");
        boolean hasEnd = dims.keySet().contains("minecraft:the_end");

        if (hasNether && hasEnd) return root;

        CompoundBinaryTag.Builder dimsBuilder = CompoundBinaryTag.builder().put(dims);

        try {
            Path levelDir = Bukkit.getServer().getLevelDirectory();
            Path mainWgs = levelDir.resolve(WGS_FILE);
            CompoundBinaryTag mainDims = null;
            if (Files.isRegularFile(mainWgs)) {
                CompoundBinaryTag wgsRoot = LevelDatNbt.read(mainWgs);
                CompoundBinaryTag wgsData = wgsRoot.keySet().contains("data") ? wgsRoot.getCompound("data") : wgsRoot;
                if (wgsData.keySet().contains("dimensions")) {
                    mainDims = wgsData.getCompound("dimensions");
                }
            }
            if (mainDims == null) {
                Path mainLevelDat = levelDir.resolve("level.dat");
                if (Files.isRegularFile(mainLevelDat)) {
                    CompoundBinaryTag mainRoot = LevelDatNbt.read(mainLevelDat);
                    CompoundBinaryTag mainData = LevelDatNbt.getData(mainRoot);
                    if (mainData.keySet().contains("WorldGenSettings")) {
                        mainDims = mainData.getCompound("WorldGenSettings").getCompound("dimensions");
                    }
                }
            }
            if (mainDims != null) {
                if (!hasNether && mainDims.keySet().contains("minecraft:the_nether")) {
                    dimsBuilder.put("minecraft:the_nether", mainDims.get("minecraft:the_nether"));
                    hasNether = true;
                }
                if (!hasEnd && mainDims.keySet().contains("minecraft:the_end")) {
                    dimsBuilder.put("minecraft:the_end", mainDims.get("minecraft:the_end"));
                    hasEnd = true;
                }
            }
        } catch (Throwable ignored) {}

        // Synthesize standard vanilla Nether and End stems if not found on disk
        if (!hasNether) {
            dimsBuilder.put("minecraft:the_nether", CompoundBinaryTag.builder()
                    .putString("type", "minecraft:the_nether")
                    .put("generator", CompoundBinaryTag.builder()
                            .putString("type", "minecraft:noise")
                            .putString("settings", "minecraft:nether")
                            .put("biome_source", CompoundBinaryTag.builder()
                                    .putString("type", "minecraft:multi_noise")
                                    .putString("preset", "minecraft:nether")
                                    .build())
                            .build())
                    .build());
        }
        if (!hasEnd) {
            dimsBuilder.put("minecraft:the_end", CompoundBinaryTag.builder()
                    .putString("type", "minecraft:the_end")
                    .put("generator", CompoundBinaryTag.builder()
                            .putString("type", "minecraft:noise")
                            .putString("settings", "minecraft:end")
                            .put("biome_source", CompoundBinaryTag.builder()
                                    .putString("type", "minecraft:the_end")
                                    .build())
                            .build())
                    .build());
        }

        CompoundBinaryTag.Builder wgsBuilder = CompoundBinaryTag.builder().put(wgs);
        wgsBuilder.put("dimensions", dimsBuilder.build());

        CompoundBinaryTag.Builder dataBuilder = CompoundBinaryTag.builder().put(data);
        dataBuilder.put("WorldGenSettings", wgsBuilder.build());

        CompoundBinaryTag.Builder rootBuilder = CompoundBinaryTag.builder().put(root);
        rootBuilder.put(LevelDatNbt.DATA_KEY, dataBuilder.build());
        return rootBuilder.build();
    }

    static @NotNull CompoundBinaryTag sanitizeDimensionsForVanilla(@NotNull CompoundBinaryTag root) {
        CompoundBinaryTag data = LevelDatNbt.getData(root);
        if (!data.keySet().contains("WorldGenSettings")) return root;

        CompoundBinaryTag wgs = data.getCompound("WorldGenSettings");
        if (!wgs.keySet().contains("dimensions")) return root;

        CompoundBinaryTag dims = wgs.getCompound("dimensions");
        CompoundBinaryTag.Builder cleanDims = CompoundBinaryTag.builder();

        String overworldKey = primaryDimensionKey(root);
        BinaryTag primaryStem = dims.get(overworldKey);
        if (primaryStem == null && !dims.keySet().isEmpty()) {
            String firstKey = dims.keySet().iterator().next();
            primaryStem = dims.get(firstKey);
        }

        if (primaryStem instanceof CompoundBinaryTag stemComp) {
            // Ensure custom plugin generator types (like "Voyage:void") are not left raw in vanilla exports
            if (stemComp.keySet().contains("generator")) {
                CompoundBinaryTag gen = stemComp.getCompound("generator");
                String genType = gen.getString("type", "");
                if (!genType.isEmpty() && !genType.startsWith("minecraft:")) {
                    CompoundBinaryTag.Builder stemBuilder = CompoundBinaryTag.builder().put(stemComp);
                    stemBuilder.putString("type", VANILLA_OVERWORLD_KEY);
                    stemBuilder.put("generator", voidFlatGenerator());
                    primaryStem = stemBuilder.build();
                }
            }
            cleanDims.put(VANILLA_OVERWORLD_KEY, primaryStem);
        } else if (primaryStem != null) {
            cleanDims.put(VANILLA_OVERWORLD_KEY, primaryStem);
        }

        // Preserve vanilla nether and end if present
        if (dims.keySet().contains("minecraft:the_nether")) {
            cleanDims.put("minecraft:the_nether", dims.get("minecraft:the_nether"));
        }
        if (dims.keySet().contains("minecraft:the_end")) {
            cleanDims.put("minecraft:the_end", dims.get("minecraft:the_end"));
        }

        CompoundBinaryTag.Builder wgsBuilder = CompoundBinaryTag.builder().put(wgs);
        wgsBuilder.put("dimensions", cleanDims.build());

        CompoundBinaryTag.Builder dataBuilder = CompoundBinaryTag.builder().put(data);
        dataBuilder.put("WorldGenSettings", wgsBuilder.build());

        CompoundBinaryTag.Builder rootBuilder = CompoundBinaryTag.builder().put(root);
        rootBuilder.put(LevelDatNbt.DATA_KEY, dataBuilder.build());
        return rootBuilder.build();
    }

    static @NotNull CompoundBinaryTag applyVanillaVoidGenerator(@NotNull CompoundBinaryTag root) {
        CompoundBinaryTag data = LevelDatNbt.getData(root);
        if (!data.keySet().contains("WorldGenSettings")) return root;

        CompoundBinaryTag worldGenSettings = data.getCompound("WorldGenSettings");
        if (!worldGenSettings.keySet().contains("dimensions")) return root;

        CompoundBinaryTag dimensions = worldGenSettings.getCompound("dimensions");
        String patchKey = primaryDimensionKey(root);
        if (!dimensions.keySet().contains(patchKey) && !dimensions.keySet().isEmpty()) {
            patchKey = dimensions.keySet().iterator().next();
        }
        if (!dimensions.keySet().contains(patchKey)) return root;

        CompoundBinaryTag overworldDim = dimensions.getCompound(patchKey);
        CompoundBinaryTag.Builder dimBuilder = CompoundBinaryTag.builder().put(overworldDim);
        dimBuilder.putString("type", VANILLA_OVERWORLD_KEY);

        // Preserve existing flat generator if it already has non-air block layers
        if (!hasNonAirFlatLayers(overworldDim)) {
            dimBuilder.put("generator", voidFlatGenerator());
        }

        CompoundBinaryTag.Builder cleanDims = CompoundBinaryTag.builder();
        cleanDims.put(VANILLA_OVERWORLD_KEY, dimBuilder.build());

        for (String key : dimensions.keySet()) {
            if (key.equals("minecraft:the_nether") || key.equals("minecraft:the_end")) {
                cleanDims.put(key, dimensions.get(key));
            }
        }

        CompoundBinaryTag.Builder wgsBuilder = CompoundBinaryTag.builder().put(worldGenSettings);
        wgsBuilder.put("dimensions", cleanDims.build());

        CompoundBinaryTag.Builder dataBuilder = CompoundBinaryTag.builder().put(data);
        dataBuilder.put("WorldGenSettings", wgsBuilder.build());

        CompoundBinaryTag.Builder rootBuilder = CompoundBinaryTag.builder().put(root);
        rootBuilder.put(LevelDatNbt.DATA_KEY, dataBuilder.build());
        return rootBuilder.build();
    }

    private static boolean hasNonAirFlatLayers(@NotNull CompoundBinaryTag dimTag) {
        if (!dimTag.keySet().contains("generator")) return false;
        CompoundBinaryTag gen = dimTag.getCompound("generator");
        if (!"minecraft:flat".equals(gen.getString("type", ""))) return false;
        if (!gen.keySet().contains("settings")) return false;
        CompoundBinaryTag settings = gen.getCompound("settings");
        if (!settings.keySet().contains("layers") || !(settings.get("layers") instanceof ListBinaryTag layers)) {
            return false;
        }
        for (BinaryTag elem : layers) {
            if (elem instanceof CompoundBinaryTag layer) {
                int height = layer.getInt("height", 1);
                String block = layer.getString("block", "minecraft:air").trim().toLowerCase(Locale.ROOT);
                if (height > 0 && !block.equals("minecraft:air") && !block.equals("air")
                        && !block.equals("minecraft:cave_air") && !block.equals("cave_air")
                        && !block.equals("minecraft:void_air") && !block.equals("void_air")) {
                    return true;
                }
            }
        }
        return false;
    }

    private static @NotNull CompoundBinaryTag voidFlatGenerator() {
        ListBinaryTag.Builder<CompoundBinaryTag> layers = ListBinaryTag.builder(BinaryTagTypes.COMPOUND);
        layers.add(CompoundBinaryTag.builder()
                .putString("block", "minecraft:air")
                .putInt("height", 1)
                .build());

        CompoundBinaryTag settings = CompoundBinaryTag.builder()
                .put("layers", layers.build())
                .putString("biome", "minecraft:the_void")
                .putByte("features", (byte) 0)
                .putByte("lakes", (byte) 0)
                .build();

        return CompoundBinaryTag.builder()
                .putString("type", "minecraft:flat")
                .put("settings", settings)
                .build();
    }

    private void zipWorldTree(@NotNull Path worldDir, @NotNull String base, @NotNull String overworldBase,
                              @NotNull ZipOutputStream zip, @NotNull Set<String> addedEntries) throws IOException {
        try {
            Files.walkFileTree(worldDir, new SimpleFileVisitor<>() {
                @Override
                public FileVisitResult preVisitDirectory(@NotNull Path dir, @NotNull BasicFileAttributes attrs) {
                    if (dir.equals(worldDir)) return FileVisitResult.CONTINUE;
                    if (SKIP_DIRS.contains(fileName(dir))) return FileVisitResult.SKIP_SUBTREE;
                    String rel = relativePath(dir) + "/";
                    try {
                        if (isDimensionPayloadPath(dir)) {
                            putDirEntry(zip, overworldBase + rel, addedEntries);
                        } else {
                            putDirEntry(zip, base + rel, addedEntries);
                        }
                    } catch (IOException e) {
                        throw new UncheckedIOException(e);
                    }
                    return FileVisitResult.CONTINUE;
                }

                @Override
                public FileVisitResult visitFile(@NotNull Path file, @NotNull BasicFileAttributes attrs) {
                    String name = fileName(file);
                    if (SKIP_FILES.contains(name) || name.equals(LEVEL_DAT) || name.startsWith(".voyage-")) {
                        return FileVisitResult.CONTINUE;
                    }
                    String rel = relativePath(file);
                    try {
                        if (isDimensionPayloadPath(file)) {
                            addFileEntry(zip, overworldBase + rel, file, addedEntries);
                            if (name.endsWith(".mca")) {
                                logger.info("  Zipped region file: " + overworldBase + rel + " (" + attrs.size() + " bytes)");
                            }
                        } else {
                            addFileEntry(zip, base + rel, file, addedEntries);
                        }
                    } catch (IOException e) {
                        throw new UncheckedIOException(e);
                    }
                    return FileVisitResult.CONTINUE;
                }

                private String relativePath(@NotNull Path path) {
                    return worldDir.relativize(path).toString().replace('\\', '/');
                }

                private boolean isDimensionPayloadPath(@NotNull Path path) {
                    Path rel = worldDir.relativize(path);
                    if (rel.getNameCount() == 0) return false;
                    return DIMENSION_PAYLOAD_DIRS.contains(rel.getName(0).toString());
                }
            });
        } catch (UncheckedIOException wrapped) {
            throw wrapped.getCause();
        }
    }

    private static void putDirEntry(@NotNull ZipOutputStream zip, @NotNull String name, @NotNull Set<String> addedEntries) throws IOException {
        String cleanName = name.endsWith("/") ? name : name + "/";
        if (addedEntries.add(cleanName)) {
            zip.putNextEntry(new ZipEntry(cleanName));
            zip.closeEntry();
        }
    }

    private static void addFileEntry(@NotNull ZipOutputStream zip, @NotNull String entryName,
                                     @NotNull Path file, @NotNull Set<String> addedEntries) throws IOException {
        if (addedEntries.add(entryName)) {
            zip.putNextEntry(new ZipEntry(entryName));
            try (InputStream in = Files.newInputStream(file)) {
                in.transferTo(zip);
            }
            zip.closeEntry();
        }
    }

    private static @NotNull String sanitizeFolderName(@NotNull String name) {
        String cleaned = name.replaceAll("[^A-Za-z0-9_.\\- ]", "_").trim();
        return cleaned.isEmpty() ? "voyage-export" : cleaned;
    }

    private static @NotNull String fileName(@NotNull Path path) {
        return path.getFileName() == null ? "" : path.getFileName().toString();
    }

    private static void safeDelete(@NotNull Path path) {
        try {
            Files.deleteIfExists(path);
        } catch (IOException ignored) {}
    }
}