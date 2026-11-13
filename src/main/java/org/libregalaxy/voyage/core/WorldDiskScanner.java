package org.libregalaxy.voyage.core;

import org.bukkit.Bukkit;
import org.bukkit.NamespacedKey;
import org.bukkit.plugin.java.JavaPlugin;

import java.io.File;
import java.io.IOException;
import java.nio.file.FileVisitResult;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.SimpleFileVisitor;
import java.nio.file.attribute.BasicFileAttributes;
import java.util.LinkedHashSet;
import java.util.Set;

public class WorldDiskScanner {

    private final JavaPlugin plugin;
    private final VoyageWorldManager manager;

    public WorldDiskScanner(JavaPlugin plugin, VoyageWorldManager manager) {
        this.plugin = plugin;
        this.manager = manager;
    }

    /**
     * Resolves the on-disk directories that may contain dimension payloads.
     */
    public File[] getDimensionRoots() {
        Set<File> roots = new LinkedHashSet<>();

        File worldContainer = null;
        try {
            worldContainer = Bukkit.getWorldContainer();
        } catch (Throwable ignored) {
            // getWorldContainer() may throw or return null in mocked environments.
        }
        if (worldContainer != null) {
            roots.add(new File(worldContainer, "dimensions"));
        }

        return roots.toArray(new File[0]);
    }

    public Set<String> scanDiskForUnmanagedWorlds() {
        Set<String> discovered = new LinkedHashSet<>();

        for (File dims : getDimensionRoots()) {
            if (!dims.exists() || !dims.isDirectory()) continue;
            File[] namespaces = dims.listFiles(File::isDirectory);
            if (namespaces == null) continue;

            for (File nsDir : namespaces) {
                File[] keys = nsDir.listFiles(File::isDirectory);
                if (keys == null) continue;

                for (File kDir : keys) {
                    if (new File(kDir, ".voyage-temp").exists() || new File(kDir, ".voyage-deleted").exists()) {
                        continue;
                    }

                    if (new File(kDir, "level.dat").exists() || new File(kDir, "region").exists()) {
                        try {
                            NamespacedKey parsed = NamespacedKey.fromString(nsDir.getName() + ":" + kDir.getName());
                            if (parsed != null && manager.getManagedWorld(parsed).isEmpty() && Bukkit.getWorld(parsed) == null) {
                                discovered.add(parsed.asString());
                            }
                        } catch (IllegalArgumentException ignored) {}
                    }
                }
            }
        }
        return discovered;
    }

    public void purgeOrphanedTempWorlds() {
        File[] staleStagingEntries = snapshotStaleStagingEntries();

        Bukkit.getScheduler().runTaskAsynchronously(plugin, () -> {
            int purged = 0;
            for (File dimsDir : getDimensionRoots()) {
                if (!dimsDir.exists() || !dimsDir.isDirectory()) continue;

                File[] namespaces = dimsDir.listFiles(File::isDirectory);
                if (namespaces == null) continue;

                for (File nsDir : namespaces) {
                    File[] keys = nsDir.listFiles(File::isDirectory);
                    if (keys == null) continue;

                    for (File kDir : keys) {
                        if (new File(kDir, ".voyage-temp").exists() || new File(kDir, ".voyage-deleted").exists()) {
                            plugin.getLogger().info("Purging orphaned temporary world: " + nsDir.getName() + ":" + kDir.getName());
                            manager.deleteDirectory(kDir);
                            purged++;
                        }
                    }
                }
            }
            if (purged > 0) {
                plugin.getLogger().info("Purged " + purged + " orphaned temporary world(s) from disk.");
            }

            int purgedStaging = 0;
            for (File stagingEntry : staleStagingEntries) {
                if (!stagingEntry.exists()) continue;
                try {
                    if (stagingEntry.isDirectory()) {
                        manager.deleteDirectory(stagingEntry);
                    } else {
                        Files.deleteIfExists(stagingEntry.toPath());
                    }
                    purgedStaging++;
                } catch (Exception e) {
                    plugin.getLogger().warning("Failed to purge orphaned staging entry " + stagingEntry.getName() + ": " + e.getMessage());
                }
            }
            if (purgedStaging > 0) {
                plugin.getLogger().info("Purged " + purgedStaging + " orphaned import staging entry(s) from plugins/Voyage/staging/.");
            }
        });
    }

    private File[] snapshotStaleStagingEntries() {
        if (plugin == null || plugin.getDataFolder() == null) {
            return new File[0];
        }
        File stagingDir = new File(plugin.getDataFolder(), "staging");
        if (!stagingDir.isDirectory()) {
            return new File[0];
        }
        File[] entries = stagingDir.listFiles();
        return entries != null ? entries : new File[0];
    }
}