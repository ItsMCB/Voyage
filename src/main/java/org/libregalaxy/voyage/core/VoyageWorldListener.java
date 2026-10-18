package org.libregalaxy.voyage.core;

import io.papermc.paper.event.world.WorldDifficultyChangeEvent;
import io.papermc.paper.event.world.WorldGameRuleChangeEvent;
import org.bukkit.Bukkit;
import org.bukkit.GameRule;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.world.WorldUnloadEvent;
import org.jetbrains.annotations.NotNull;
import org.libregalaxy.voyage.api.events.VoyageWorldUnloadEvent;

public class VoyageWorldListener implements Listener {

    private final VoyageWorldManager worldManager;

    public VoyageWorldListener(@NotNull VoyageWorldManager worldManager) {
        this.worldManager = worldManager;
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onBukkitWorldUnload(@NotNull WorldUnloadEvent event) {
        worldManager.getManagedWorld(event.getWorld().getKey()).ifPresent(mw -> {
            Bukkit.getPluginManager().callEvent(new VoyageWorldUnloadEvent(mw));
        });
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onWorldDifficultyChange(@NotNull WorldDifficultyChangeEvent event) {
        worldManager.getManagedWorld(event.getWorld().getKey()).ifPresent(mw -> {
            if (mw instanceof ManagedWorldImpl impl && mw.getDifficulty() != event.getDifficulty()) {
                impl.setDifficultyInternal(event.getDifficulty());
                worldManager.saveState();
            }
        });
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onWorldGameRuleChange(@NotNull WorldGameRuleChangeEvent event) {
        worldManager.getManagedWorld(event.getWorld().getKey()).ifPresent(mw -> {
            if (mw instanceof ManagedWorldImpl impl) {
                GameRule<?> rule = event.getGameRule();
                String valStr = event.getValue();
                Object parsedVal = WorldConfigService.parseGameRuleValue(rule, valStr);
                if (parsedVal != null) {
                    impl.setGameRuleInternal(rule, parsedVal);
                    worldManager.saveState();
                }
            }
        });
    }
}