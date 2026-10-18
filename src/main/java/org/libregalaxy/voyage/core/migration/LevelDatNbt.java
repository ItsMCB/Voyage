package org.libregalaxy.voyage.core.migration;

import net.kyori.adventure.nbt.BinaryTag;
import net.kyori.adventure.nbt.BinaryTagIO;
import net.kyori.adventure.nbt.CompoundBinaryTag;
import org.bukkit.Bukkit;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;

/**
 * The single place in Voyage that reads and writes level.dat (and its
 * world_gen_settings.dat companion) with adventure-nbt.
 *
 * <p>Vanilla's level.dat contract:
 * <ul>
 *   <li>GZIP-compressed NBT, big-endian;</li>
 *   <li>a root compound holding the level data under {@code "Data"};</li>
 *   <li>{@code Data.DataVersion} (Int) &gt; 0. A missing, zero, or mistyped
 *       DataVersion causes vanilla clients to reject world load;</li>
 *   <li>{@code Data.version} (Int) — the level-dat format version (19133);</li>
 *   <li>{@code Data.Version} (Compound) — version details (Id, Name, Series, Snapshot)
 *       read by vanilla's singleplayer world selection menu.</li>
 * </ul>
 *
 * <p>26.x may keep the data version (and generator settings) in the companion
 * file {@code data/minecraft/world_gen_settings.dat} instead of level.dat, so
 * version resolution looks at both the root compound and its {@code Data}
 * child. Every level.dat Voyage writes goes through
 * {@link #writeVanillaSafe} — the contract cannot regress.
 */
public final class LevelDatNbt {

    public static final String DATA_KEY = "Data";
    public static final String DATA_VERSION_KEY = "DataVersion";
    public static final String FORMAT_VERSION_KEY = "version";
    public static final int FORMAT_VERSION_VALUE = 19133;

    /** 1.21.11-era stamp used only when no live server or file can be probed. */
    public static final int LAST_RESORT_DATA_VERSION = 4671;

    private static volatile int cachedServerDataVersion = 0;

    private LevelDatNbt() {}

    // ------------------------------------------------------------------ read

    /**
     * Reads a level.dat (or any NBT file written the same way). Vanilla always
     * GZIP-compresses these files; uncompressed files are tolerated. Runtime
     * parse failures (malformed NBT) are wrapped as IOExceptions so callers
     * can fall back gracefully instead of aborting a whole export batch.
     */
    public static @NotNull CompoundBinaryTag read(@NotNull Path file) throws IOException {
        try {
            return BinaryTagIO.reader().read(file, BinaryTagIO.Compression.GZIP);
        } catch (IOException | RuntimeException notGzip) {
            try {
                return BinaryTagIO.reader().read(file, BinaryTagIO.Compression.NONE);
            } catch (IOException | RuntimeException rawFailure) {
                rawFailure.addSuppressed(notGzip);
                throw new IOException("Unreadable NBT file " + file + ": " + rawFailure.getMessage(), rawFailure);
            }
        }
    }

    /** The {@code Data} compound of a level.dat root, or an empty compound. */
    public static @NotNull CompoundBinaryTag getData(@NotNull CompoundBinaryTag root) {
        return root.keySet().contains(DATA_KEY) ? root.getCompound(DATA_KEY) : CompoundBinaryTag.empty();
    }

    // ----------------------------------------------------------------- write

    /** Writes a level.dat enforcing the vanilla contract. */
    public static void writeVanillaSafe(@NotNull Path file, @NotNull CompoundBinaryTag root,
                                        @Nullable String levelName) throws IOException {
        CompoundBinaryTag safe = ensureVanillaLevelData(root, levelName);
        if (resolveDataVersion(safe, 0) <= 0) {
            throw new IOException("Refusing to write " + file + " with DataVersion <= 0 — vanilla would refuse to load it");
        }
        write(file, safe);
    }

    /** Plain GZIP write without contract enforcement. */
    public static void write(@NotNull Path file, @NotNull CompoundBinaryTag root) throws IOException {
        Path parent = file.getParent();
        if (parent != null && !Files.exists(parent)) Files.createDirectories(parent);
        BinaryTagIO.writer().write(root, file, BinaryTagIO.Compression.GZIP);
    }

    // -------------------------------------------------------------- contract

    /**
     * Enforces the vanilla contract in memory:
     * <ul>
     *   <li>ensures DataVersion reflects at least the server's data version so modern layouts load cleanly;</li>
     *   <li>repairs a missing/zero DataVersion with the running server's data version;</li>
     *   <li>backfills {@code Data.version}, {@code Data.GameType} and {@code Data.LevelName} when absent;</li>
     *   <li>populates {@code Data.Version} compound so singleplayer displays the exact version;</li>
     *   <li>mirrors the DataVersion on the root compound.</li>
     * </ul>
     */
    public static @NotNull CompoundBinaryTag ensureVanillaLevelData(@NotNull CompoundBinaryTag root,
                                                                    @Nullable String levelName) {
        return ensureVanillaLevelData(root, levelName, serverDataVersion());
    }

    /** Test-friendly variant with an explicit fallback data version. */
    public static @NotNull CompoundBinaryTag ensureVanillaLevelData(@NotNull CompoundBinaryTag root,
                                                                    @Nullable String levelName,
                                                                    int fallbackDataVersion) {
        CompoundBinaryTag data = getData(root);
        int existingVersion = resolveDataVersion(root, 0);
        int dataVersion = Math.max(existingVersion, fallbackDataVersion);

        CompoundBinaryTag.Builder dataBuilder = CompoundBinaryTag.builder();
        for (Map.Entry<String, ? extends BinaryTag> entry : data) {
            String key = entry.getKey();
            if (key.equalsIgnoreCase("requiresConversion") || key.equalsIgnoreCase("conversionStatus")) {
                continue;
            }
            dataBuilder.put(key, entry.getValue());
        }

        dataBuilder.putInt(DATA_VERSION_KEY, dataVersion);
        if (!data.keySet().contains(FORMAT_VERSION_KEY)) {
            dataBuilder.putInt(FORMAT_VERSION_KEY, FORMAT_VERSION_VALUE);
        }
        if (!data.keySet().contains("GameType")) {
            dataBuilder.putInt("GameType", 1);
        }
        if (levelName != null && !levelName.isBlank()) {
            dataBuilder.putString("LevelName", levelName);
        }

        // Always synchronize Data.Version so the Minecraft singleplayer menu displays the modern version
        String versionName = "1.21.4";
        try {
            versionName = Bukkit.getMinecraftVersion();
        } catch (Throwable ignored) {}

        CompoundBinaryTag versionCompound = CompoundBinaryTag.builder()
                .putInt("Id", dataVersion)
                .putString("Name", versionName)
                .putString("Series", "main")
                .putByte("Snapshot", (byte) 0)
                .build();
        dataBuilder.put("Version", versionCompound);

        CompoundBinaryTag.Builder rootBuilder = CompoundBinaryTag.builder().put(root);
        rootBuilder.put(DATA_KEY, dataBuilder.build());
        rootBuilder.putInt(DATA_VERSION_KEY, dataVersion);
        return rootBuilder.build();
    }

    /**
     * Best DataVersion found in the root compound or its {@code Data} child
     * (0 when neither carries a valid one).
     */
    public static int resolveDataVersion(@NotNull CompoundBinaryTag root, int fallback) {
        int best = 0;
        if (root.keySet().contains(DATA_VERSION_KEY)) {
            best = Math.max(best, root.getInt(DATA_VERSION_KEY, 0));
        }
        CompoundBinaryTag data = getData(root);
        if (data.keySet().contains(DATA_VERSION_KEY)) {
            best = Math.max(best, data.getInt(DATA_VERSION_KEY, 0));
        }
        return best > 0 ? best : fallback;
    }

    /** Reads the DataVersion of an NBT file; 0 when unreadable. */
    public static int readDataVersion(@NotNull Path file) {
        if (!Files.isRegularFile(file)) return 0;
        try {
            return resolveDataVersion(read(file), 0);
        } catch (IOException | RuntimeException unreadable) {
            return 0;
        }
    }

    // ------------------------------------------------------- server version

    /**
     * The data version this server writes, resolved from the main level's own
     * files (preferred) or {@link org.bukkit.UnsafeValues} (fallback).
     */
    @SuppressWarnings("deprecation")
    public static int serverDataVersion() {
        int cached = cachedServerDataVersion;
        if (cached > 0) return cached;

        int resolved = 0;
        try {
            Path levelDir = Bukkit.getServer().getLevelDirectory();
            resolved = readDataVersion(levelDir.resolve("level.dat"));
            if (resolved <= 0) {
                resolved = readDataVersion(levelDir.resolve("data").resolve("minecraft")
                        .resolve("world_gen_settings.dat"));
            }
        } catch (Throwable ignored) {}
        if (resolved <= 0) {
            try {
                resolved = Bukkit.getUnsafe().getDataVersion();
            } catch (Throwable ignored) {}
        }
        if (resolved > 0) {
            cachedServerDataVersion = resolved;
            return resolved;
        }
        return LAST_RESORT_DATA_VERSION;
    }
}