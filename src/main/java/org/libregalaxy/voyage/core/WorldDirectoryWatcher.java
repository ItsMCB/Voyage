package org.libregalaxy.voyage.core;

import org.bukkit.plugin.java.JavaPlugin;

import java.util.Collections;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Lightweight, on-demand TTL cache for unmanaged world names.
 * Replaces the heavy background OS WatchService daemon thread and debouncing delays.
 */
public class WorldDirectoryWatcher {

    private final JavaPlugin plugin;
    private final VoyageWorldManager manager;
    private final Set<String> unmanagedCache = ConcurrentHashMap.newKeySet();

    private volatile long lastScanTime = 0L;
    private static final long CACHE_TTL_MS = 5000L; // 5-second TTL cache for tab completions

    public WorldDirectoryWatcher(JavaPlugin plugin, VoyageWorldManager manager) {
        this.plugin = plugin;
        this.manager = manager;
    }

    public void start() {
        populateInitialCache();
    }

    public void stop() {
        unmanagedCache.clear();
    }

    public Set<String> getUnmanagedCache() {
        long now = System.currentTimeMillis();
        if (now - lastScanTime > CACHE_TTL_MS) {
            refreshCache();
            lastScanTime = now;
        }
        return unmanagedCache;
    }

    public void populateInitialCache() {
        refreshCache();
        lastScanTime = System.currentTimeMillis();
    }

    private synchronized void refreshCache() {
        unmanagedCache.clear();
        unmanagedCache.addAll(manager.scanDiskForUnmanagedWorlds());
    }
}