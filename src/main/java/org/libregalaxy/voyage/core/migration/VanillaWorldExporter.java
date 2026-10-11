package org.libregalaxy.voyage.core.migration;

import net.kyori.adventure.nbt.BinaryTag;
import net.kyori.adventure.nbt.BinaryTagTypes;
import net.kyori.adventure.nbt.CompoundBinaryTag;
import net.kyori.adventure.nbt.ListBinaryTag;
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
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.logging.Logger;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

/**
 * Exports a managed Paper 26.x world as a zip archive containing a folder a
 * vanilla 26.x client can drop into its saves directory.
 *
 * <p>World data: the server-written level.dat is copied and patched (never
 * rebuilt) through {@link LevelDatNbt}, the 26.x split-out generator settings
 * are merged back in, and void worlds are normalized to the vanilla
 * flat-void preset. Region files are copied byte-for-byte.
 *
 * <p>Player data: the primary player is embedded as level.dat's Data.Player —
 * the legacy singleplayer mechanism, which 26.x clients still honor — and
 * every exported player also receives a playerdata/&lt;uuid&gt;.dat entry.
 *
 * <p>Naming: the folder inside the zip is derived from the world key (unique
 * per world, so exports never collide), while the optional levelName becomes
 * the LevelName shown in the vanilla world list.
 *
 * <p>Singleplayer quality of life: the exported save has "Allow Cheats"
 * enabled ({@code Data.allowCommands}); the flag is singleplayer/LAN-only
 * and ignored by dedicated servers.
 *
 * <p>World border: 26.x stores the border as flat tags directly inside
 * level.dat's Data compound, mirrored here in both camelCase and snake_case.
 */
public final class VanillaWorldExporter {

    private static final String LEVEL_DAT = "level.dat";
    private static final String WGS_FILE = "data/minecraft/world_gen_settings.dat";
    private static final String PLAYER_DATA_DIR = "playerdata";

    /** Server/multiplayer files with no meaning in a vanilla singleplayer save. */
    private static final Set<String> SKIP_FILES = Set.of("uid.dat", "session.lock", "level.dat_old", "paper-world.yml");
    private static final Set<String> SKIP_DIRS = Set.of("playerdata", "players", "stats", "advancements", "paper");

    /** The world border at export time, captured live by the manager. */
    public record WorldBorderData(
            double centerX, double centerZ, double size,
            double damagePerBlock, double safeZone,
            int warningBlocks, int warningTime
    ) {}

    private final Logger logger;

    public VanillaWorldExporter(@NotNull Logger logger) {
        this.logger = logger;
    }

    /** Export without player data (previous behavior). */
    public void export(@NotNull Path worldDir, @NotNull String name, boolean voidWorld,
                       @NotNull Path targetZip) throws IOException {
        export(worldDir, name, null, voidWorld, List.of(), null, targetZip);
    }

    /**
     * @param worldDir      the managed world's data directory (must contain level.dat)
     * @param folderName    unique folder name inside the zip (derived from the world key)
     * @param levelName     human-readable LevelName for the vanilla world list;
     *                      null or blank falls back to the folder name
     * @param voidWorld     true for void generator worlds
     * @param players       Voyage player state to attach to the export
     * @param primaryPlayer player whose state is embedded as level.dat's
     *                      Data.Player (the vanilla singleplayer host); also
     *                      written to playerdata/ like everyone else
     * @param targetZip     destination zip (parent directories are created)
     */
    public void export(@NotNull Path worldDir, @NotNull String folderName, @Nullable String levelName,
                       boolean voidWorld,
                       @NotNull List<VanillaPlayerData.ExportedPlayer> players,
                       @Nullable UUID primaryPlayer,
                       @NotNull Path targetZip) throws IOException {
        if (!Files.isDirectory(worldDir)) {
            throw new IOException("World directory does not exist: " + worldDir);
        }
        Path sourceLevelDat = worldDir.resolve(LEVEL_DAT);
        if (!Files.isRegularFile(sourceLevelDat)) {
            throw new IOException("Cannot export " + worldDir + ": level.dat is missing");
        }

        String effectiveLevelName = levelName != null && !levelName.isBlank() ? levelName : folderName;

        Path parent = targetZip.toAbsolutePath().getParent();
        if (parent != null) Files.createDirectories(parent);

        Path tempLevelDat = Files.createTempFile("voyage-level-dat", ".dat");
        Map<UUID, Path> tempPlayerData = new LinkedHashMap<>();
        try {
            // 1. Build the vanilla-safe level.dat, embedding the primary player.
            CompoundBinaryTag root = LevelDatNbt.read(sourceLevelDat);
            root = mergeWorldGenSettings(root, worldDir);
            root = stripPlayerTag(root);
            String dimensionKey = primaryDimensionKey(root);
            if (voidWorld) {
                root = applyVanillaVoidGenerator(root);
            }

            // World border: mirror whatever level.dat (or a legacy source)
            // carries into the flat Data tags 26.x actually reads.
            WorldBorderData border = resolveWorldBorder(root, worldDir);
            if (border != null) {
                root = applyWorldBorder(root, border);
            }

            // Singleplayer quality of life: "Allow Cheats" on.
            root = enableCheats(root);

            Map<UUID, CompoundBinaryTag> normalizedPlayers = new LinkedHashMap<>();
            for (VanillaPlayerData.ExportedPlayer player : players) {
                normalizedPlayers.put(player.uuid(), VanillaPlayerData.normalizeForVanilla(
                        player, dimensionKey, readSpawn(root), LevelDatNbt.serverDataVersion()));
            }

            UUID primary = null;
            if (primaryPlayer != null) {
                if (normalizedPlayers.containsKey(primaryPlayer)) {
                    primary = primaryPlayer;
                } else {
                    logger.info("No Voyage player data found for " + primaryPlayer
                            + " — exporting without embedded player state.");
                }
            }
            if (primary != null) {
                CompoundBinaryTag data = LevelDatNbt.getData(root);
                CompoundBinaryTag.Builder dataBuilder = CompoundBinaryTag.builder().put(data);
                dataBuilder.put("Player", normalizedPlayers.get(primary));
                CompoundBinaryTag.Builder rootBuilder = CompoundBinaryTag.builder().put(root);
                rootBuilder.put(LevelDatNbt.DATA_KEY, dataBuilder.build());
                root = rootBuilder.build();
            }

            LevelDatNbt.writeVanillaSafe(tempLevelDat, root, effectiveLevelName);
            if (LevelDatNbt.readDataVersion(tempLevelDat) <= 0) {
                throw new IOException("Exported level.dat failed verification (DataVersion <= 0)");
            }

            // 2. Build + verify each playerdata/<uuid>.dat. Player data files
            //    have the player compound as the ROOT (no "Data" wrapper), so
            //    they use the plain writer — normalize already stamped the
            //    root-level DataVersion.
            for (Map.Entry<UUID, CompoundBinaryTag> entry : normalizedPlayers.entrySet()) {
                Path temp = Files.createTempFile("voyage-playerdata-", ".dat");
                LevelDatNbt.write(temp, entry.getValue());
                if (LevelDatNbt.readDataVersion(temp) <= 0) {
                    throw new IOException("Exported player data for " + entry.getKey()
                            + " failed verification (DataVersion <= 0)");
                }
                tempPlayerData.put(entry.getKey(), temp);
            }

            // 3. Zip everything under a named world folder.
            String base = sanitizeFolderName(folderName) + "/";
            try (ZipOutputStream zip = new ZipOutputStream(Files.newOutputStream(
                    targetZip, StandardOpenOption.CREATE, StandardOpenOption.TRUNCATE_EXISTING))) {
                putDirEntry(zip, base);
                addFileEntry(zip, base + LEVEL_DAT, tempLevelDat);

                // Converted Voyage player data — legacy playerdata/ location
                // only. (The server's own playerdata folder is excluded by
                // the tree walk, so only Voyage-managed players appear.)
                if (!tempPlayerData.isEmpty()) {
                    putDirEntry(zip, base + PLAYER_DATA_DIR + "/");
                    for (Map.Entry<UUID, Path> entry : tempPlayerData.entrySet()) {
                        addFileEntry(zip, base + PLAYER_DATA_DIR + "/" + entry.getKey() + ".dat",
                                entry.getValue());
                    }
                }

                zipWorldTree(worldDir, base, zip);
            }
        } catch (UncheckedIOException unchecked) {
            safeDelete(targetZip); // never leave a half-written archive behind
            throw unchecked.getCause();
        } catch (IOException io) {
            safeDelete(targetZip);
            throw io;
        } finally {
            try {
                Files.deleteIfExists(tempLevelDat);
            } catch (IOException ignored) {}
            for (Path temp : tempPlayerData.values()) {
                try {
                    Files.deleteIfExists(temp);
                } catch (IOException ignored) {}
            }
        }
    }

    // -------------------------------------------------------- singleplayer QoL

    /**
     * "Allow Cheats" ({@code Data.allowCommands} = 1) so the vanilla
     * singleplayer host can change game mode etc. The tag is
     * singleplayer/LAN-only — dedicated servers govern commands through
     * ops.json and ignore it — so this never affects the running Voyage
     * server. Both spellings are written; readers ignore the unknown one.
     */
    private static @NotNull CompoundBinaryTag enableCheats(@NotNull CompoundBinaryTag root) {
        CompoundBinaryTag data = LevelDatNbt.getData(root);
        CompoundBinaryTag.Builder dataBuilder = CompoundBinaryTag.builder().put(data);
        dataBuilder.putByte("allowCommands", (byte) 1);
        dataBuilder.putByte("allow_commands", (byte) 1);
        CompoundBinaryTag.Builder rootBuilder = CompoundBinaryTag.builder().put(root);
        rootBuilder.put(LevelDatNbt.DATA_KEY, dataBuilder.build());
        return rootBuilder.build();
    }

    // ---------------------------------------------------------- world border

    /**
     * Writes the border as FLAT tags directly inside level.dat's Data
     * compound — the 26.x layout (including the two lerp tags; warning tags
     * are doubles). The snake_case mirrors are harmless duplicates for schema
     * eras that renamed them; unknown keys are ignored by every reader.
     * Idempotent. Public so VoyageWorldManager can patch the on-disk
     * level.dat with the live border before exporting.
     */
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

    /**
     * Resolves the border: 26.x flat Data tags first, then legacy sources
     * (a nested compound, or the data/world_border.dat companion file some
     * layouts use).
     */
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
            Path borderFile = worldDir.resolve("data").resolve("world_border.dat");
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

    // ------------------------------------------------------------ world data

    /**
     * 26.x keeps generator settings (and often the DataVersion) in
     * data/minecraft/world_gen_settings.dat. Vanilla clients that only read
     * level.dat need both folded back in.
     */
    private @NotNull CompoundBinaryTag mergeWorldGenSettings(@NotNull CompoundBinaryTag root,
                                                             @NotNull Path worldDir) {
        CompoundBinaryTag data = LevelDatNbt.getData(root);
        if (data.keySet().contains("WorldGenSettings")) {
            return root; // already embedded (pre-split layout or Voyage-synthesized files)
        }

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

            CompoundBinaryTag.Builder dataBuilder = CompoundBinaryTag.builder().put(data);
            dataBuilder.put("WorldGenSettings", wgsData);
            CompoundBinaryTag.Builder rootBuilder = CompoundBinaryTag.builder().put(root);
            rootBuilder.put(LevelDatNbt.DATA_KEY, dataBuilder.build());
            if (bestVersion > 0) {
                rootBuilder.putInt(LevelDatNbt.DATA_VERSION_KEY, bestVersion);
            }
            return rootBuilder.build();
        } catch (IOException e) {
            logger.warning("Could not merge world_gen_settings.dat into the exported level.dat: " + e.getMessage());
            return root;
        }
    }

    /** The old stale Player tag (if any) is removed; ours is injected afterwards. */
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

    /**
     * A dimension id the exported save actually defines — players and respawn
     * points must reference one of these, never a managed planet:... key.
     */
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
                    if (key.endsWith("overworld")) return key;
                }
                for (String key : dims.keySet()) {
                    if (key.startsWith("minecraft:")) return key;
                }
                if (!dims.keySet().isEmpty()) {
                    return dims.keySet().iterator().next();
                }
            }
        }
        return "minecraft:overworld";
    }

    private static double[] readSpawn(@NotNull CompoundBinaryTag root) {
        CompoundBinaryTag data = LevelDatNbt.getData(root);
        if (!data.keySet().contains("SpawnX") || !data.keySet().contains("SpawnY")
                || !data.keySet().contains("SpawnZ")) {
            return null;
        }
        return new double[]{
                data.getInt("SpawnX", 0) + 0.5,
                data.getInt("SpawnY", 64),
                data.getInt("SpawnZ", 0) + 0.5
        };
    }

    /**
     * Vanilla only regenerates unexplored chunks — exported void worlds must
     * regenerate them as void. Mirrors how WorldImportService writes void
     * generator tags so server and client agree on the format.
     */
    static @NotNull CompoundBinaryTag applyVanillaVoidGenerator(@NotNull CompoundBinaryTag root) {
        CompoundBinaryTag data = LevelDatNbt.getData(root);
        if (!data.keySet().contains("WorldGenSettings")) return root;

        CompoundBinaryTag worldGenSettings = data.getCompound("WorldGenSettings");
        if (!worldGenSettings.keySet().contains("dimensions")) return root;

        CompoundBinaryTag dimensions = worldGenSettings.getCompound("dimensions");
        String patchKey = null;
        if (dimensions.keySet().size() == 1) {
            patchKey = dimensions.keySet().iterator().next();
        } else {
            for (String key : dimensions.keySet()) {
                if (key.endsWith("overworld")) {
                    patchKey = key;
                    break;
                }
            }
        }
        if (patchKey == null) return root;

        CompoundBinaryTag.Builder dimBuilder = CompoundBinaryTag.builder().put(dimensions.getCompound(patchKey));
        dimBuilder.put("generator", voidFlatGenerator());

        CompoundBinaryTag.Builder dimsBuilder = CompoundBinaryTag.builder().put(dimensions);
        dimsBuilder.put(patchKey, dimBuilder.build());

        CompoundBinaryTag.Builder wgsBuilder = CompoundBinaryTag.builder().put(worldGenSettings);
        wgsBuilder.put("dimensions", dimsBuilder.build());

        CompoundBinaryTag.Builder dataBuilder = CompoundBinaryTag.builder().put(data);
        dataBuilder.put("WorldGenSettings", wgsBuilder.build());

        CompoundBinaryTag.Builder rootBuilder = CompoundBinaryTag.builder().put(root);
        rootBuilder.put(LevelDatNbt.DATA_KEY, dataBuilder.build());
        return rootBuilder.build();
    }

    /** NBT form of VoyageAPI.VANILLA_VOID_SETTINGS: flat, one air layer, the_void biome. */
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

    private void zipWorldTree(@NotNull Path worldDir, @NotNull String base, @NotNull ZipOutputStream zip) throws IOException {
        try {
            Files.walkFileTree(worldDir, new SimpleFileVisitor<>() {
                @Override
                public FileVisitResult preVisitDirectory(@NotNull Path dir, @NotNull BasicFileAttributes attrs) {
                    if (dir.equals(worldDir)) return FileVisitResult.CONTINUE;
                    if (SKIP_DIRS.contains(fileName(dir))) return FileVisitResult.SKIP_SUBTREE;
                    try {
                        putDirEntry(zip, entryName(dir) + "/");
                    } catch (IOException e) {
                        throw new UncheckedIOException(e);
                    }
                    return FileVisitResult.CONTINUE;
                }

                @Override
                public FileVisitResult visitFile(@NotNull Path file, @NotNull BasicFileAttributes attrs) {
                    String name = fileName(file);
                    if (SKIP_FILES.contains(name) || name.equals(LEVEL_DAT)) {
                        return FileVisitResult.CONTINUE;
                    }
                    try {
                        addFileEntry(zip, entryName(file), file);
                    } catch (IOException e) {
                        throw new UncheckedIOException(e);
                    }
                    return FileVisitResult.CONTINUE;
                }

                private String entryName(@NotNull Path path) {
                    return base + worldDir.relativize(path).toString().replace('\\', '/');
                }
            });
        } catch (UncheckedIOException wrapped) {
            throw wrapped.getCause();
        }
    }

    private static void putDirEntry(@NotNull ZipOutputStream zip, @NotNull String name) throws IOException {
        zip.putNextEntry(new ZipEntry(name));
        zip.closeEntry();
    }

    private static void addFileEntry(@NotNull ZipOutputStream zip, @NotNull String entryName,
                                     @NotNull Path file) throws IOException {
        zip.putNextEntry(new ZipEntry(entryName));
        try (InputStream in = Files.newInputStream(file)) {
            in.transferTo(zip);
        }
        zip.closeEntry();
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