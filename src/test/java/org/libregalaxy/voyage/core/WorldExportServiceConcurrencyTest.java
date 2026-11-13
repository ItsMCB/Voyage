package org.libregalaxy.voyage.core;

import org.bukkit.Bukkit;
import org.bukkit.NamespacedKey;
import org.bukkit.World;
import org.bukkit.plugin.java.JavaPlugin;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.libregalaxy.voyage.VoyageContext;
import org.mockito.MockedStatic;

import java.nio.file.Path;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class WorldExportServiceConcurrencyTest {

    @TempDir
    Path tempDir;

    private VoyageContext context;
    private JavaPlugin plugin;
    private VoyageWorldManager worldManager;
    private WorldFileService fileService;
    private WorldExportService exportService;

    @BeforeEach
    void setUp() {
        plugin = mock(JavaPlugin.class);
        context = mock(VoyageContext.class);
        worldManager = mock(VoyageWorldManager.class);
        fileService = mock(WorldFileService.class);

        when(context.getPlugin()).thenReturn(plugin);
        exportService = new WorldExportService(context, worldManager, fileService);
    }

    @Test
    void exportWorld_whenAlreadyClaimed_failsImmediatelyAndKeepsExistingClaimIntact() {
        NamespacedKey mainKey = NamespacedKey.minecraft("overworld");
        NamespacedKey busyKey = NamespacedKey.fromString("custom:busy_world");

        World mainWorld = mock(World.class);
        when(mainWorld.getKey()).thenReturn(mainKey);

        World busyWorld = mock(World.class);
        when(busyWorld.getKey()).thenReturn(busyKey);

        when(worldManager.getManagedWorld(busyKey)).thenReturn(Optional.of(mock(ManagedWorldImpl.class)));
        assertTrue(exportService.claimExport(busyKey));

        try (MockedStatic<Bukkit> bukkit = mockStatic(Bukkit.class)) {
            bukkit.when(Bukkit::getWorlds).thenReturn(List.of(mainWorld, busyWorld));
            bukkit.when(() -> Bukkit.getWorld(busyKey)).thenReturn(busyWorld);

            CompletableFuture<Void> duplicateExport = exportService.exportWorld(busyKey, tempDir.resolve("out.zip"));

            assertTrue(duplicateExport.isCompletedExceptionally(),
                    "Concurrent export on an already-claimed world must fail immediately");
            assertTrue(exportService.isExporting(busyKey),
                    "Rejected concurrent export must not release the active export claim");
        }
    }

    @Test
    void exportWorld_onLoadedUnmanagedWorld_failsWithoutUnloadingWorld() {
        NamespacedKey mainKey = NamespacedKey.minecraft("overworld");
        NamespacedKey unmanagedKey = NamespacedKey.fromString("custom:unmanaged_loaded");

        World mainWorld = mock(World.class);
        when(mainWorld.getKey()).thenReturn(mainKey);

        World unmanagedWorld = mock(World.class);
        when(unmanagedWorld.getKey()).thenReturn(unmanagedKey);

        when(worldManager.getManagedWorld(unmanagedKey)).thenReturn(Optional.empty());

        try (MockedStatic<Bukkit> bukkit = mockStatic(Bukkit.class)) {
            bukkit.when(Bukkit::getWorlds).thenReturn(List.of(mainWorld, unmanagedWorld));
            bukkit.when(() -> Bukkit.getWorld(unmanagedKey)).thenReturn(unmanagedWorld);

            CompletableFuture<Void> future = exportService.exportWorld(unmanagedKey, tempDir.resolve("unmanaged.zip"));

            assertTrue(future.isCompletedExceptionally(),
                    "Exporting a loaded unmanaged world must fail immediately");
            CompletionException ex = assertThrows(CompletionException.class, future::join);
            assertTrue(ex.getCause().getMessage().contains("not managed by Voyage"));
            verify(worldManager, never()).unload(any(), any(), anyBoolean());
        }
    }
}