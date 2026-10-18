package org.libregalaxy.voyage.core;

import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.Server;
import org.bukkit.NamespacedKey;
import org.bukkit.World;
import org.bukkit.configuration.file.FileConfiguration;
import org.bukkit.entity.Player;
import org.bukkit.plugin.Plugin;
import org.bukkit.plugin.java.JavaPlugin;
import org.bukkit.scheduler.BukkitScheduler;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.libregalaxy.voyage.VoyageContext;
import org.mockito.MockedStatic;

import java.nio.file.Path;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.logging.Logger;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class VoyageWorldManagerTest {

    @TempDir
    Path tempDir;

    @Test
    void unload_evacuatesPlayersToFallback() {
        VoyageContext mockContext = mock(VoyageContext.class);
        JavaPlugin mockPlugin = mock(JavaPlugin.class);
        when(mockContext.getPlugin()).thenReturn(mockPlugin);
        when(mockPlugin.getDataFolder()).thenReturn(tempDir.toFile());

        // Mock config for loadState
        FileConfiguration mockConfig = mock(FileConfiguration.class);
        when(mockPlugin.getConfig()).thenReturn(mockConfig);
        when(mockConfig.getConfigurationSection("world-groups")).thenReturn(null); // No groups to load

        // Mock logger to prevent NPEs in catch blocks
        Logger mockLogger = mock(Logger.class);
        when(mockPlugin.getLogger()).thenReturn(mockLogger);

        try (MockedStatic<Bukkit> mockedBukkit = mockStatic(Bukkit.class)) {
            // 1. Mock Server and LevelDirectory
            Server mockServer = mock(Server.class);
            when(mockServer.getLevelDirectory()).thenReturn(tempDir.resolve("level"));
            mockedBukkit.when(Bukkit::getServer).thenReturn(mockServer);

            // 2. Mock Scheduler to run tasks synchronously
            BukkitScheduler mockScheduler = mock(BukkitScheduler.class);
            when(mockScheduler.runTask(any(Plugin.class), any(Runnable.class))).thenAnswer(inv -> {
                ((Runnable) inv.getArgument(1)).run();
                return null;
            });
            when(mockScheduler.runTaskAsynchronously(any(Plugin.class), any(Runnable.class))).thenAnswer(inv -> {
                ((Runnable) inv.getArgument(1)).run();
                return null;
            });
            when(mockScheduler.runTaskLater(any(Plugin.class), any(Runnable.class), anyLong())).thenAnswer(inv -> {
                ((Runnable) inv.getArgument(1)).run();
                return null;
            });
            when(mockScheduler.runTaskLaterAsynchronously(any(Plugin.class), any(Runnable.class), anyLong())).thenAnswer(inv -> {
                ((Runnable) inv.getArgument(1)).run();
                return null;
            });
            mockedBukkit.when(Bukkit::getScheduler).thenReturn(mockScheduler);

            // 3. Mock Worlds
            NamespacedKey key = NamespacedKey.fromString("custom:world_to_unload");
            NamespacedKey fallbackKey = NamespacedKey.fromString("custom:fallback");
            World mockWorld = mock(World.class);
            World fallbackWorld = mock(World.class);

            when(mockWorld.getKey()).thenReturn(key);
            when(fallbackWorld.getKey()).thenReturn(fallbackKey);

            mockedBukkit.when(() -> Bukkit.getWorld(key)).thenReturn(mockWorld);
            mockedBukkit.when(() -> Bukkit.getWorld(fallbackKey)).thenReturn(fallbackWorld);
            mockedBukkit.when(() -> Bukkit.unloadWorld(any(World.class), anyBoolean())).thenReturn(true);

            // 4. Mock Bukkit.getWorlds() to prevent NPE in determineEvacuationWorld
            mockedBukkit.when(Bukkit::getWorlds).thenReturn(List.of(mockWorld, fallbackWorld));

            // 5. Mock Player inside the world
            Player mockPlayer = mock(Player.class);
            when(mockPlayer.teleportAsync(any())).thenReturn(CompletableFuture.completedFuture(true));
            when(mockWorld.getPlayers()).thenReturn(List.of(mockPlayer));

            // 6. Instantiate Manager (Constructor will use mocked statics)
            VoyageWorldManager manager = new VoyageWorldManager(mockContext);

            // 7. Execute unload
            CompletableFuture<Boolean> future = manager.unload(key, fallbackKey, true);
            future.join();

            // 8. Verify evacuation
            verify(mockPlayer).teleportAsync(any(Location.class));

            manager.shutdown();
        }
    }
}