package org.libregalaxy.voyage.core;

import org.bukkit.Bukkit;
import org.bukkit.NamespacedKey;
import org.bukkit.plugin.java.JavaPlugin;
import org.bukkit.scheduler.BukkitScheduler;
import org.junit.jupiter.api.Test;
import org.mockito.MockedStatic;
import org.libregalaxy.voyage.VoyageContext;
import org.libregalaxy.voyage.api.ManagedWorld;

import java.io.File;
import java.nio.file.Files;
import java.util.Optional;
import java.util.logging.Logger;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class WorldBuilderImplValidationTest {

    @Test
    void duplicateManagedWorld_isRejected() throws Exception {
        VoyageWorldManager manager = mock(VoyageWorldManager.class);
        JavaPlugin plugin = mock(JavaPlugin.class);
        when(plugin.getLogger()).thenReturn(mock(Logger.class));
        VoyageContext context = mock(VoyageContext.class);
        NamespacedKey key = NamespacedKey.fromString("custom:duplicate");

        when(manager.getPlugin()).thenReturn(plugin);
        when(manager.claimBuild(key)).thenReturn(true);

        // 1. Pre-checks pass (no existing world found yet)
        when(manager.getManagedWorld(any())).thenReturn(Optional.empty());

        // 2. Validation passes (directory doesn't exist yet)
        when(manager.resolveWorldFolder(any())).thenReturn(
                Files.createTempDirectory("voyage-missing").resolve("does-not-exist").toFile()
        );

        // 3. Simulate the race: another thread registered it just before we did
        when(manager.registerNewWorld(any(ManagedWorldImpl.class))).thenReturn(false);

        WorldBuilderImpl builder = new WorldBuilderImpl(key, manager);

        // Mock scheduler to run tasks synchronously so the future completes immediately
        BukkitScheduler scheduler = mock(BukkitScheduler.class);
        doAnswer(inv -> { ((Runnable) inv.getArgument(1)).run(); return null; })
                .when(scheduler).runTaskAsynchronously(any(), any(Runnable.class));
        doAnswer(inv -> { ((Runnable) inv.getArgument(1)).run(); return null; })
                .when(scheduler).runTask(any(), any(Runnable.class));

        try (MockedStatic<Bukkit> bukkit = mockStatic(Bukkit.class)) {
            bukkit.when(() -> Bukkit.getWorld(key)).thenReturn(null);
            bukkit.when(Bukkit::getScheduler).thenReturn(scheduler);

            var result = builder.create();

            assertTrue(result.isCompletedExceptionally());
            verify(manager).releaseBuild(key);
            // Verify we did NOT delete the directory, because another thread owns it now
            verify(manager, never()).deleteDirectory(any(File.class));
        }
    }

    @Test
    void importExisting_requiresWorldDirectoryOrLoadedWorld() throws Exception {
        VoyageWorldManager manager = mock(VoyageWorldManager.class);
        JavaPlugin plugin = mock(JavaPlugin.class);
        when(plugin.getLogger()).thenReturn(mock(Logger.class));
        when(manager.getPlugin()).thenReturn(plugin);

        when(manager.claimBuild(any())).thenReturn(true);
        when(manager.getManagedWorld(any())).thenReturn(Optional.empty());
        when(manager.resolveWorldFolder(any())).thenReturn(
                Files.createTempDirectory("voyage-missing").resolve("does-not-exist").toFile()
        );

        NamespacedKey key = NamespacedKey.fromString("custom:missing");
        WorldBuilderImpl builder = new WorldBuilderImpl(key, manager);
        builder.importExisting(true);

        try (MockedStatic<Bukkit> bukkit = mockStatic(Bukkit.class)) {
            bukkit.when(() -> Bukkit.getWorld(key)).thenReturn(null);
            var result = builder.create();
            assertTrue(result.isCompletedExceptionally());
            verify(manager).releaseBuild(key);
        }
    }
}