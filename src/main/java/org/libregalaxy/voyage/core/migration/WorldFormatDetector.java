package org.libregalaxy.voyage.core.migration;

import org.libregalaxy.voyage.api.VoyageAPI;
import org.jetbrains.annotations.NotNull;

import java.nio.file.Files;
import java.nio.file.Path;

/**
 * Detects the on-disk format of a world directory so WorldImportService can
 * pick the correct migration path. Extracted from WorldImportService so that
 * class only orchestrates — format knowledge lives here.
 */
public final class WorldFormatDetector {

    public @NotNull VoyageAPI.ImportStrategy detect(@NotNull Path sourceDir) {
        if (!Files.isDirectory(sourceDir)) {
            return VoyageAPI.ImportStrategy.UNKNOWN;
        }
        if (Files.exists(sourceDir.resolve("data/paper/metadata.dat"))) {
            return VoyageAPI.ImportStrategy.PAPER_26_X;
        }
        if (Files.exists(sourceDir.resolve("data/minecraft/chunk_tickets.dat"))) {
            return VoyageAPI.ImportStrategy.VANILLA_26_X;
        }
        if (Files.exists(sourceDir.resolve("paper-world.yml")) || Files.exists(sourceDir.resolve("uid.dat"))) {
            return VoyageAPI.ImportStrategy.PAPER_1_21_11;
        }
        if (Files.exists(sourceDir.resolve("level.dat"))) {
            return VoyageAPI.ImportStrategy.VANILLA_1_21_11;
        }
        return VoyageAPI.ImportStrategy.UNKNOWN;
    }
}