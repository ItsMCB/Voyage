package org.libregalaxy.voyage.core;

import org.bukkit.Bukkit;
import org.bukkit.NamespacedKey;
import org.bukkit.Server;
import org.bukkit.World;
import org.bukkit.plugin.java.JavaPlugin;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Optional;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class WorldDiskScannerTest {

    @TempDir
    Path tempDir;

    private JavaPlugin plugin;
    private VoyageWorldManager manager;
    private Server server;

    @BeforeEach
    void setUp() {
        plugin = mock(JavaPlugin.class);
        manager = mock(VoyageWorldManager.class);
        server = mock(Server.class);

        when(server.getLevelDirectory()).thenReturn(tempDir);
        when(manager.getManagedWorld(any())).thenReturn(Optional.empty());
    }

    @AfterEach
    void tearDown() {
        org.mockito.Mockito.framework().clearInlineMocks();
    }

    @Test
    void scan_findsModernDimensionFoldersWithRegionData() throws Exception {
        Path world = tempDir.resolve("dimensions/custom/moon");
        Files.createDirectories(world.resolve("region"));
        Files.writeString(world.resolve("region/r.0.0.mca"), "placeholder");

        try (var bukkit = org.mockito.Mockito.mockStatic(Bukkit.class)) {
            bukkit.when(Bukkit::getServer).thenReturn(server);
            bukkit.when(Bukkit::getWorldContainer).thenReturn(tempDir.toFile());
            bukkit.when(() -> Bukkit.getWorld(any(NamespacedKey.class))).thenReturn(null);

            Set<String> found = new WorldDiskScanner(plugin, manager).scanDiskForUnmanagedWorlds();

            assertTrue(found.contains("custom:moon"));
        }
    }

    @Test
    void scan_ignoresVoyageTempAndDeletedMarkers() throws Exception {
        Path tempWorld = tempDir.resolve("dimensions/custom/temp");
        Path deletedWorld = tempDir.resolve("dimensions/custom/deleted");

        Files.createDirectories(tempWorld.resolve("region"));
        Files.createDirectories(deletedWorld.resolve("region"));
        Files.writeString(tempWorld.resolve(".voyage-temp"), "");
        Files.writeString(deletedWorld.resolve(".voyage-deleted"), "");

        try (var bukkit = org.mockito.Mockito.mockStatic(Bukkit.class)) {
            bukkit.when(Bukkit::getServer).thenReturn(server);
            bukkit.when(Bukkit::getWorldContainer).thenReturn(tempDir.toFile());
            bukkit.when(() -> Bukkit.getWorld(any(NamespacedKey.class))).thenReturn(null);

            Set<String> found = new WorldDiskScanner(plugin, manager).scanDiskForUnmanagedWorlds();

            assertFalse(found.contains("custom:temp"));
            assertFalse(found.contains("custom:deleted"));
        }
    }

    @Test
    void scan_doesNotReportManagedOrLoadedWorlds() throws Exception {
        Path managed = tempDir.resolve("dimensions/custom/managed");
        Path loaded = tempDir.resolve("dimensions/custom/loaded");
        Files.createDirectories(managed.resolve("region"));
        Files.createDirectories(loaded.resolve("region"));

        NamespacedKey managedKey = NamespacedKey.fromString("custom:managed");
        NamespacedKey loadedKey = NamespacedKey.fromString("custom:loaded");

        when(manager.getManagedWorld(managedKey))
                .thenReturn(Optional.of(mock(org.libregalaxy.voyage.api.ManagedWorld.class)));

        World loadedWorld = mock(World.class);
        when(loadedWorld.getKey()).thenReturn(loadedKey);

        try (var bukkit = org.mockito.Mockito.mockStatic(Bukkit.class)) {
            bukkit.when(Bukkit::getServer).thenReturn(server);
            bukkit.when(Bukkit::getWorldContainer).thenReturn(tempDir.toFile());
            bukkit.when(() -> Bukkit.getWorld(loadedKey)).thenReturn(loadedWorld);
            bukkit.when(() -> Bukkit.getWorld(managedKey)).thenReturn(null);

            Set<String> found = new WorldDiskScanner(plugin, manager).scanDiskForUnmanagedWorlds();

            assertFalse(found.contains("custom:managed"));
            assertFalse(found.contains("custom:loaded"));
        }
    }
}
