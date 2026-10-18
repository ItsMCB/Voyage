package org.libregalaxy.voyage.core;

import net.kyori.adventure.nbt.CompoundBinaryTag;
import org.bukkit.Bukkit;
import org.bukkit.NamespacedKey;
import org.bukkit.World;
import org.bukkit.WorldType;
import org.bukkit.plugin.java.JavaPlugin;
import org.jetbrains.annotations.NotNull;
import org.libregalaxy.voyage.api.VoyageAPI;
import org.libregalaxy.voyage.core.migration.LevelDatNbt;

import java.io.File;
import java.io.IOException;
import java.nio.file.*;
import java.nio.file.attribute.BasicFileAttributes;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;

public class WorldFileService {

    private final JavaPlugin plugin;

    public WorldFileService(@NotNull JavaPlugin plugin) {
        this.plugin = plugin;
    }

    private static boolean hasRegionData(@NotNull File folder) {
        File regionDir = new File(folder, "region");
        if (!regionDir.isDirectory()) return false;
        String[] mcas = regionDir.list((d, name) -> name.endsWith(".mca"));
        return mcas != null && mcas.length > 0;
    }

    @NotNull
    public File resolveWorldFolder(@NotNull NamespacedKey key) {
        World loaded = Bukkit.getWorld(key);
        if (loaded != null && hasRegionData(loaded.getWorldFolder())) {
            return loaded.getWorldFolder();
        }

        if (key.equals(NamespacedKey.minecraft("overworld"))) {
            return Bukkit.getServer().getLevelDirectory().toFile();
        }

        List<File> candidates = new ArrayList<>();
        if (loaded != null) {
            candidates.add(loaded.getWorldFolder());
        }

        // 1. levelDirectory/dimensions/<namespace>/<key>
        candidates.add(Bukkit.getServer().getLevelDirectory()
                .resolve("dimensions")
                .resolve(key.getNamespace())
                .resolve(key.getKey())
                .toFile());

        // 2. worldContainer/dimensions/<namespace>/<key>
        candidates.add(new File(Bukkit.getWorldContainer(), "dimensions" + File.separator + key.getNamespace() + File.separator + key.getKey()));

        // 3. worldContainer/<key>
        candidates.add(new File(Bukkit.getWorldContainer(), key.getKey()));

        // 4. worldContainer/<namespace>_<key>
        candidates.add(new File(Bukkit.getWorldContainer(), key.getNamespace() + "_" + key.getKey()));

        // Prioritize the candidate folder that actually contains .mca region chunk files!
        for (File candidate : candidates) {
            if (hasRegionData(candidate)) {
                return candidate;
            }
        }

        // Fallback to whichever candidate exists on disk
        for (File candidate : candidates) {
            if (candidate.exists()) {
                return candidate;
            }
        }

        return candidates.get(1); // default to candidate 1
    }

    public void copyDirectory(@NotNull File source, @NotNull File target) throws IOException {
        Path src = source.toPath(), tgt = target.toPath();
        if (!Files.exists(src)) return;

        Files.walkFileTree(src, new SimpleFileVisitor<>() {
            @Override
            public FileVisitResult preVisitDirectory(Path dir, BasicFileAttributes attrs) throws IOException {
                if (dir.getFileName().toString().equals("dimensions") && dir.getParent().equals(src)) {
                    return FileVisitResult.SKIP_SUBTREE;
                }
                Path targetDir = tgt.resolve(src.relativize(dir));
                if (!Files.exists(targetDir)) Files.createDirectories(targetDir);
                return FileVisitResult.CONTINUE;
            }

            @Override
            public FileVisitResult visitFile(Path file, BasicFileAttributes attrs) throws IOException {
                String name = file.getFileName().toString();
                Path parent = file.getParent();
                Path grandParent = parent != null ? parent.getParent() : null;

                boolean isPaperMetadata = name.equals("metadata.dat")
                        && parent != null && parent.getFileName().toString().equals("paper")
                        && grandParent != null && grandParent.getFileName().toString().equals("data");

                if (!name.equals("uid.dat") && !name.equals("session.lock") && !isPaperMetadata) {
                    Files.copy(file, tgt.resolve(src.relativize(file)), StandardCopyOption.REPLACE_EXISTING);
                }
                return FileVisitResult.CONTINUE;
            }
        });
    }

    public CompletableFuture<Void> deleteDirectory(@NotNull File folder) {
        if (!folder.exists()) return CompletableFuture.completedFuture(null);

        CompletableFuture<Void> future = new CompletableFuture<>();
        deleteDirectoryWithRetry(folder.toPath(), 3, future);
        return future;
    }

    private void deleteDirectoryWithRetry(Path path, int attemptsLeft, CompletableFuture<Void> future) {
        Bukkit.getScheduler().runTaskAsynchronously(plugin, () -> {
            try {
                if (Files.exists(path)) {
                    Files.walkFileTree(path, new SimpleFileVisitor<>() {
                        @Override
                        public FileVisitResult visitFile(Path file, BasicFileAttributes attrs) throws IOException {
                            Files.deleteIfExists(file);
                            return FileVisitResult.CONTINUE;
                        }

                        @Override
                        public FileVisitResult postVisitDirectory(Path dir, IOException exc) throws IOException {
                            if (exc != null) throw exc;
                            Files.deleteIfExists(dir);
                            return FileVisitResult.CONTINUE;
                        }
                    });
                }
                future.complete(null);
            } catch (IOException e) {
                if (attemptsLeft > 1) {
                    Bukkit.getScheduler().runTaskLater(plugin, () -> {
                        deleteDirectoryWithRetry(path, attemptsLeft - 1, future);
                    }, 5L);
                } else {
                    future.completeExceptionally(e);
                }
            }
        });
    }

    public void ensureWorldGenFiles(@NotNull NamespacedKey worldKey, @NotNull File worldDir, @NotNull ManagedWorldImpl mw, @NotNull WorldImportService importService) {
        Path dimDir = worldDir.toPath();
        Path levelDat = dimDir.resolve("level.dat");
        Path wgsFile = dimDir.resolve("data").resolve("minecraft").resolve("world_gen_settings.dat");

        boolean hasChunks = Files.isDirectory(dimDir.resolve("region"))
                || Files.isDirectory(dimDir.resolve("entities"))
                || Files.isDirectory(dimDir.resolve("poi"));

        if (Files.isRegularFile(levelDat) && (hasChunks || Files.isRegularFile(wgsFile))) {
            repairLevelDatIfNeeded(levelDat);
            return;
        }

        boolean isVoid = VoyageAPI.isVoidWorld(mw.getGenerator().orElse(null), mw.getGeneratorSettings().orElse(null));

        WorldImportService.DimensionGenInfo genInfo = new WorldImportService.DimensionGenInfo(
                isVoid ? WorldType.FLAT : mw.getWorldType(),
                isVoid ? VoyageAPI.VANILLA_VOID_SETTINGS : mw.getGeneratorSettings().orElse(null),
                isVoid ? (plugin.getName() + ":void") : mw.getGenerator().orElse(null),
                mw.getEnvironment(),
                mw.getSeed(),
                !isVoid && mw.isGenerateStructures()
        );

        WorldImportService.ParsedWorldData fallbackData = new WorldImportService.ParsedWorldData(
                null,
                LevelDatNbt.serverDataVersion(),
                mw.getSeed(),
                !isVoid && mw.isGenerateStructures(),
                Map.of(worldKey.asString(), genInfo)
        );

        importService.writeDimensionGenFiles(dimDir, worldKey, genInfo, fallbackData);
    }

    public void repairLevelDatIfNeeded(@NotNull Path levelDat) {
        try {
            CompoundBinaryTag root = LevelDatNbt.read(levelDat);
            CompoundBinaryTag data = LevelDatNbt.getData(root);
            boolean healthy = LevelDatNbt.resolveDataVersion(root, 0) > 0
                    && data.getInt(LevelDatNbt.FORMAT_VERSION_KEY, 0) > 0;
            if (healthy) return;
            LevelDatNbt.writeVanillaSafe(levelDat, root, null);
            plugin.getLogger().info("Repaired missing DataVersion/format version in " + levelDat);
        } catch (IOException e) {
            plugin.getLogger().warning("Could not verify level.dat at " + levelDat + ": " + e.getMessage());
        }
    }
}