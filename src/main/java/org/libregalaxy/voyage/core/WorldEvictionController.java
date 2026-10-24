package org.libregalaxy.voyage.core;

import org.bukkit.Bukkit;
import org.bukkit.NamespacedKey;
import org.bukkit.World;
import org.bukkit.configuration.file.FileConfiguration;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerChangedWorldEvent;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.event.world.WorldLoadEvent;
import org.bukkit.event.world.WorldUnloadEvent;
import org.bukkit.scheduler.BukkitTask;
import org.jetbrains.annotations.NotNull;
import org.libregalaxy.voyage.VoyageContext;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;
import java.util.logging.Level;

/**
 * Monitors active dimension occupancy and automatically unloads idle worlds
 * after a configurable timeout to ensure server RAM remains stable at 1,000+ planets.
 */
public class WorldEvictionController implements Listener {

    private final VoyageContext context;
    private final VoyageWorldManager worldManager;
    private final Map<NamespacedKey, Long> emptyWorlds = new ConcurrentHashMap<>();
    private BukkitTask task;

    public WorldEvictionController(@NotNull VoyageContext context, @NotNull VoyageWorldManager worldManager) {
        this.context = context;
        this.worldManager = worldManager;
    }

    public void start() {
        boolean enabled = true;
        try {
            if (context.getPlugin() != null && context.getPlugin().getConfig() != null) {
                FileConfiguration config = context.getPlugin().getConfig();
                enabled = config.getBoolean("eviction.enabled", true);
            }
        } catch (Throwable ignored) {}

        if (!enabled) return;

        long intervalTicks = 20L * 30L; // Check every 30 seconds

        try {
            if (Bukkit.getScheduler() != null && context.getPlugin() != null) {
                this.task = Bukkit.getScheduler().runTaskTimer(context.getPlugin(), this::runEvictionCycle, intervalTicks, intervalTicks);
            }
        } catch (Throwable ignored) {}
    }

    public void stop() {
        if (task != null) {
            task.cancel();
            task = null;
        }
        emptyWorlds.clear();
    }

    private void runEvictionCycle() {
        long timeoutMinutes = 5L;
        try {
            if (context.getPlugin() != null && context.getPlugin().getConfig() != null) {
                timeoutMinutes = context.getPlugin().getConfig().getLong("eviction.idle-timeout-minutes", 5L);
            }
        } catch (Throwable ignored) {}

        long thresholdMs = TimeUnit.MINUTES.toMillis(Math.max(1L, timeoutMinutes));
        long now = System.currentTimeMillis();

        World defaultWorld = worldManager.getMainLevel();
        NamespacedKey mainKey = defaultWorld != null ? defaultWorld.getKey() : null;

        emptyWorlds.forEach((key, emptySince) -> {
            if (key.equals(mainKey)) {
                emptyWorlds.remove(key);
                return;
            }

            if (now - emptySince >= thresholdMs) {
                World world = Bukkit.getWorld(key);
                if (world != null && world.getPlayers().isEmpty()) {
                    worldManager.getManagedWorld(key).ifPresent(mw -> {
                        if (!mw.isTemporary()) {
                            try {
                                if (context.getPlugin() != null && context.getPlugin().getLogger() != null) {
                                    context.getPlugin().getLogger().info("Evicting idle dimension to free RAM: " + key.asString()
                                            + " (empty for " + (thresholdMs / 60000) + " minutes)");
                                }
                            } catch (Throwable ignored) {}
                            mw.unload(true);
                        }
                    });
                }
                emptyWorlds.remove(key);
            }
        });
    }

    private void checkWorldEmptiness(@NotNull World world) {
        NamespacedKey key = world.getKey();
        World main = worldManager.getMainLevel();
        if (main != null && main.getKey().equals(key)) return;

        if (world.getPlayers().isEmpty() && worldManager.getManagedWorld(key).isPresent()) {
            emptyWorlds.putIfAbsent(key, System.currentTimeMillis());
        } else {
            emptyWorlds.remove(key);
        }
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onPlayerChangeWorld(PlayerChangedWorldEvent event) {
        checkWorldEmptiness(event.getFrom());
        emptyWorlds.remove(event.getPlayer().getWorld().getKey());
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onPlayerQuit(PlayerQuitEvent event) {
        checkWorldEmptiness(event.getPlayer().getWorld());
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onWorldLoad(WorldLoadEvent event) {
        checkWorldEmptiness(event.getWorld());
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onWorldUnload(WorldUnloadEvent event) {
        emptyWorlds.remove(event.getWorld().getKey());
    }
}