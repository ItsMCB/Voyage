package org.libregalaxy.voyage.core;

import org.bukkit.Bukkit;
import org.bukkit.plugin.java.JavaPlugin;
import org.jetbrains.annotations.NotNull;

import java.util.Collections;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

/**
 * High-performance, zero-disk-I/O in-memory cache for unmanaged world names.
 * Ensures Brigadier command suggestions never block the main thread or cause disk lag spikes.
 */
public class WorldDirectoryWatcher {

    private final JavaPlugin plugin;
    private final VoyageWorldManager manager;
    private final Set<String> unmanagedCache = ConcurrentHashMap.newKeySet();

    public WorldDirectoryWatcher(@NotNull JavaPlugin plugin, @NotNull VoyageWorldManager manager) {
        this.plugin = plugin;
        this.manager = manager;
    }

    public void start() {
        // Initial scan runs asynchronously on boot to prevent watchdog lag spikes
        scanDiskAsync();
    }

    public void stop() {
        unmanagedCache.clear();
    }

    /**
     * Instantly returns cached unmanaged world names.
     * Guaranteed zero filesystem I/O: completely safe for Brigadier command suggestions.
     */
    public @NotNull Set<String> getUnmanagedCache() {
        return Collections.unmodifiableSet(unmanagedCache);
    }

    public void addUnmanaged(@NotNull String worldName) {
        unmanagedCache.add(worldName);
    }

    public void removeUnmanaged(@NotNull String worldName) {
        unmanagedCache.remove(worldName);
    }

    /**
     * Asynchronously scans the disk to populate the unmanaged world cache.
     */
    public void scanDiskAsync() {
        Bukkit.getScheduler().runTaskAsynchronously(plugin, () -> {
            Set<String> discovered = manager.scanDiskForUnmanagedWorlds();
            unmanagedCache.clear();
            unmanagedCache.addAll(discovered);
        });
    }
}