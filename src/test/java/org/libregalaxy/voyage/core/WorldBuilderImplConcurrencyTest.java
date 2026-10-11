package org.libregalaxy.voyage.core;

import org.bukkit.Bukkit;
import org.bukkit.NamespacedKey;
import org.bukkit.plugin.java.JavaPlugin;
import org.bukkit.scheduler.BukkitScheduler;
import org.junit.jupiter.api.Test;
import org.libregalaxy.voyage.VoyageContext;
import org.libregalaxy.voyage.api.ManagedWorld;
import org.mockito.MockedStatic;

import java.nio.file.Files;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import java.util.logging.Logger;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class WorldBuilderImplConcurrencyTest {

    @Test
    void create_whenRegisterFailsConcurrently_doesNotDeleteDirectoryAndFailsFuture() throws Exception {
        VoyageWorldManager manager = mock(VoyageWorldManager.class);
        JavaPlugin plugin = mock(JavaPlugin.class);
        when(plugin.getLogger()).thenReturn(mock(Logger.class));
        VoyageContext context = mock(VoyageContext.class);
        NamespacedKey key = NamespacedKey.fromString("custom:race");

        when(manager.getPlugin()).thenReturn(plugin);
        when(manager.claimBuild(key)).thenReturn(true);
        when(manager.getManagedWorld(any())).thenReturn(Optional.empty()); // Pre-check passes

        java.io.File tempDir = Files.createTempDirectory("voyage-race").toFile();
        java.io.File worldDir = new java.io.File(tempDir, "custom/race");
        when(manager.resolveWorldFolder(key)).thenReturn(worldDir);

        // Simulate the race: another thread registered it just before we did
        when(manager.registerNewWorld(any(ManagedWorldImpl.class))).thenReturn(false);

        WorldBuilderImpl builder = new WorldBuilderImpl(key, manager);

        try (MockedStatic<Bukkit> bukkit = mockStatic(Bukkit.class)) {
            bukkit.when(() -> Bukkit.getWorld(key)).thenReturn(null);

            BukkitScheduler scheduler = mock(BukkitScheduler.class);
            doAnswer(inv -> { ((Runnable) inv.getArgument(1)).run(); return null; })
                    .when(scheduler).runTaskAsynchronously(any(), any(Runnable.class));
            doAnswer(inv -> { ((Runnable) inv.getArgument(1)).run(); return null; })
                    .when(scheduler).runTask(any(), any(Runnable.class));
            bukkit.when(Bukkit::getScheduler).thenReturn(scheduler);

            CompletableFuture<ManagedWorld> future = builder.create();

            assertTrue(future.isCompletedExceptionally());
            // Verify that deleteDirectory was NOT called, because another thread owns it!
            verify(manager, never()).deleteDirectory(any(java.io.File.class));
            verify(manager).releaseBuild(key);
        }
    }
}