package org.libregalaxy.voyage.core.migration;

import org.jetbrains.annotations.NotNull;

import java.io.IOException;
import java.nio.file.DirectoryStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.logging.Logger;

/**
 * Copies world folders from legacy (1.21.11-era) layouts into the Paper 26.x
 * dimension layout. Extracted from WorldImportService.
 *
 * <p>Rules that keep migrated worlds loadable:
 * <ul>
 *   <li>chunk/entity/poi files are copied byte-for-byte — region NBT is never
 *       re-serialized, so per-chunk DataVersions stay intact;</li>
 *   <li>{@code level.dat} is copied verbatim; the caller repairs its DataVersion
 *       through {@link LevelDatNbt};</li>
 *   <li>server-proprietary files ({@code uid.dat}, {@code session.lock}) and
 *       Paper's per-world metadata are never carried across worlds;</li>
 *   <li>{@code datapacks} are included (they were previously dropped).</li>
 * </ul>
 */
public final class LegacyWorldMigrator {

    private static final String[] PAYLOAD_DIRS = {"region", "entities", "poi", "data", "datapacks"};
    private static final String[] PAYLOAD_FILES = {"level.dat", "paper-world.yml", "icon.png"};

    private final Logger logger;

    public LegacyWorldMigrator(@NotNull Logger logger) {
        this.logger = logger;
    }

    /**
     * Copies the dimension payload of a legacy world folder into a Paper 26.x
     * dimension directory (created if missing).
     */
    public void copyDimensionPayload(@NotNull Path legacyDir, @NotNull Path targetDir) throws IOException {
        Files.createDirectories(targetDir);

        for (String sub : PAYLOAD_DIRS) {
            Path source = legacyDir.resolve(sub);
            if (!Files.exists(source)) continue;
            Path destination = targetDir.resolve(sub);
            if (Files.isDirectory(source)) {
                copyDirectoryRecursively(source, destination);
            } else {
                Files.copy(source, destination, StandardCopyOption.REPLACE_EXISTING);
            }
        }

        for (String fileName : PAYLOAD_FILES) {
            Path source = legacyDir.resolve(fileName);
            if (Files.isRegularFile(source)) {
                Files.copy(source, targetDir.resolve(fileName), StandardCopyOption.REPLACE_EXISTING);
            }
        }

        logger.info("Copied legacy world data from " + legacyDir + " into " + targetDir);
    }

    /**
     * Recursive copy that skips server-proprietary files and Paper's per-world
     * metadata. Public because WorldImportService delegates its existing
     * method here (kept for test compatibility).
     */
    public void copyDirectoryRecursively(@NotNull Path source, @NotNull Path target) throws IOException {
        if (!Files.exists(target)) Files.createDirectories(target);

        try (DirectoryStream<Path> stream = Files.newDirectoryStream(source)) {
            for (Path entry : stream) {
                if (Files.isDirectory(entry)) {
                    copyDirectoryRecursively(entry, target.resolve(entry.getFileName()));
                } else if (!isServerProprietary(entry)) {
                    Files.copy(entry, target.resolve(entry.getFileName()), StandardCopyOption.REPLACE_EXISTING);
                }
            }
        }
    }

    private static boolean isServerProprietary(@NotNull Path file) {
        String fileName = file.getFileName().toString();
        if (fileName.equals("uid.dat") || fileName.equals("session.lock")) return true;

        Path parent = file.getParent();
        Path grandParent = parent != null ? parent.getParent() : null;
        return fileName.equals("metadata.dat")
                && parent != null && parent.getFileName() != null
                && parent.getFileName().toString().equals("paper")
                && grandParent != null && grandParent.getFileName() != null
                && grandParent.getFileName().toString().equals("data");
    }
}