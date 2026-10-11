package org.libregalaxy.voyage.core;

import org.bukkit.*;
import org.bukkit.configuration.file.FileConfiguration;
import org.bukkit.entity.Player;
import org.bukkit.plugin.Plugin;
import org.bukkit.plugin.java.JavaPlugin;
import org.bukkit.scheduler.BukkitScheduler;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.libregalaxy.voyage.VoyageContext;
import org.libregalaxy.voyage.api.ManagedWorld;
import org.mockbukkit.mockbukkit.MockBukkit;
import org.mockbukkit.mockbukkit.ServerMock;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class VoyageWorldManagerLifecycleTest {

    @TempDir
    Path tempDir;

    private ServerMock server;

    @BeforeEach
    void setUp() {
        server = MockBukkit.mock();
    }

    @AfterEach
    void tearDown() {
        MockBukkit.unmock();
    }

    private TestManager newManager() {
        VoyageContext context = mock(VoyageContext.class);
        JavaPlugin plugin = mock(JavaPlugin.class);
        FileConfiguration config = mock(FileConfiguration.class);

        when(context.getPlugin()).thenReturn(plugin);
        when(plugin.getDataFolder()).thenReturn(tempDir.toFile());
        when(plugin.getConfig()).thenReturn(config);
        when(config.getConfigurationSection("world-groups")).thenReturn(null);
        when(context.getService(PlayerDataManager.class)).thenReturn(mock(PlayerDataManager.class));
        when(plugin.getLogger()).thenReturn(mock(java.util.logging.Logger.class));

        BukkitScheduler scheduler = mock(BukkitScheduler.class);
        doAnswer(inv -> { ((Runnable) inv.getArgument(1)).run(); return mock(org.bukkit.scheduler.BukkitTask.class); })
                .when(scheduler).runTaskAsynchronously(any(), any(Runnable.class));
        try (var bukkit = org.mockito.Mockito.mockStatic(Bukkit.class, CALLS_REAL_METHODS)) {
            bukkit.when(Bukkit::getScheduler).thenReturn(scheduler);
            return new TestManager(context);
        }
    }

    @Test
    void delete_mainLevel_isAlwaysRejected() {
        TestManager manager = newManager();

        World main = mock(World.class);
        NamespacedKey mainKey = NamespacedKey.minecraft("overworld");
        when(main.getKey()).thenReturn(mainKey);

        try (var bukkit = org.mockito.Mockito.mockStatic(Bukkit.class, CALLS_REAL_METHODS)) {
            bukkit.when(Bukkit::getWorlds).thenReturn(List.of(main));

            assertFalse(
                    manager.deleteWorld(mainKey, null).join(),
                    "The primary level must never be deletable"
            );
        }

        manager.shutdown();
    }

    @Test
    void delete_setsExportingFlagImmediatelyBeforeSchedulerYield() {
        TestManager manager = newManager();
        NamespacedKey key = NamespacedKey.fromString("custom:delete_race");
        ManagedWorldImpl world = managedWorld(manager, key);
        assertTrue(manager.registerNewWorld(world));

        World main = mock(World.class);
        when(main.getKey()).thenReturn(NamespacedKey.minecraft("overworld"));

        // Mock scheduler to NOT run immediately
        BukkitScheduler scheduler = mock(BukkitScheduler.class);

        try (var bukkit = org.mockito.Mockito.mockStatic(Bukkit.class, CALLS_REAL_METHODS)) {
            bukkit.when(Bukkit::getWorlds).thenReturn(List.of(main));
            bukkit.when(Bukkit::getScheduler).thenReturn(scheduler);

            // Call deleteWorld, but do not execute the captured task yet
            manager.deleteWorld(key, null);

            // Assert the flag is set immediately to prevent concurrent loads
            assertTrue(manager.isExporting(key), "exportingWorlds must be marked immediately to prevent concurrent loads");
        }
        manager.shutdown();
    }

    @Test
    void delete_cancelledByEvent_keepsWorldTracked() {
        TestManager manager = newManager();
        NamespacedKey key = NamespacedKey.fromString("custom:protected");
        ManagedWorldImpl world = managedWorld(manager, key);
        assertTrue(manager.registerNewWorld(world));

        // MockBukkit's event bus is used here. Registering a cancellation listener
        // makes this a true event-contract test rather than a private-field test.
        var listener = new org.bukkit.event.Listener() {
            @org.bukkit.event.EventHandler
            public void onDelete(org.libregalaxy.voyage.api.events.VoyageWorldDeleteEvent event) {
                event.setCancelled(true);
            }
        };
        server.getPluginManager().registerEvents(listener, MockBukkit.createMockPlugin("Test"));

        BukkitScheduler scheduler = immediateScheduler();
        World main = mock(World.class);
        when(main.getKey()).thenReturn(NamespacedKey.minecraft("overworld"));
        try (var bukkit = org.mockito.Mockito.mockStatic(Bukkit.class, CALLS_REAL_METHODS)) {
            bukkit.when(Bukkit::getWorlds).thenReturn(List.of(main));
            bukkit.when(Bukkit::getScheduler).thenReturn(scheduler);
            assertFalse(manager.deleteWorld(key, null).join());
        }
        assertTrue(manager.getManagedWorld(key).isPresent());

        manager.shutdown();
    }


    @Test
    void delete_filesystemFailure_doesNotReportSuccess() {
        TestManager manager = newManager();
        NamespacedKey key = NamespacedKey.fromString("custom:filesystem_failure");
        ManagedWorldImpl world = managedWorld(manager, key);
        assertTrue(manager.registerNewWorld(world));
        manager.deleteDirectoryFailure = true;
        manager.deleteFolder = tempDir.resolve("filesystem-failure").toFile();
        assertTrue(manager.deleteFolder.mkdirs());

        BukkitScheduler scheduler = immediateScheduler();
        World main = mock(World.class);
        when(main.getKey()).thenReturn(NamespacedKey.minecraft("overworld"));
        try (var bukkit = org.mockito.Mockito.mockStatic(Bukkit.class, CALLS_REAL_METHODS)) {
            bukkit.when(Bukkit::getWorlds).thenReturn(List.of(main));
            bukkit.when(Bukkit::getScheduler).thenReturn(scheduler);
            assertFalse(
                    manager.deleteWorld(key, null).join(),
                    "A filesystem deletion failure must never be reported as successful"
            );
        }
        assertTrue(manager.getManagedWorld(key).isPresent(),
                "The world must remain tracked if its disk deletion did not complete");

        manager.shutdown();
    }

    @Test
    void untrack_unloadFailure_doesNotSilentlyDisappearFromRegistry() {
        TestManager manager = newManager();
        NamespacedKey key = NamespacedKey.fromString("custom:loaded");
        ManagedWorldImpl world = managedWorld(manager, key);
        assertTrue(manager.registerNewWorld(world));

        World bukkitWorld = mock(World.class);
        when(bukkitWorld.getKey()).thenReturn(key);

        World main = mock(World.class);
        when(main.getKey()).thenReturn(NamespacedKey.minecraft("overworld"));
        try (var bukkit = org.mockito.Mockito.mockStatic(Bukkit.class, CALLS_REAL_METHODS)) {
            bukkit.when(Bukkit::getWorlds).thenReturn(List.of(main));
            bukkit.when(() -> Bukkit.getWorld(key)).thenReturn(bukkitWorld);
            when(bukkitWorld.getPlayers()).thenReturn(List.of());

            manager.unloadResult = false;

            assertThrows(CompletionException.class, () -> manager.untrackWorld(key).join());

            assertTrue(
                    manager.getManagedWorld(key).isPresent(),
                    "Failed unload must not leave a loaded world untracked"
            );
        }

        manager.shutdown();
    }

    private BukkitScheduler immediateScheduler() {
        BukkitScheduler scheduler = mock(BukkitScheduler.class);
        doAnswer(inv -> { ((Runnable) inv.getArgument(1)).run(); return null; })
                .when(scheduler).runTask(any(), any(Runnable.class));
        return scheduler;
    }

    private ManagedWorldImpl managedWorld(VoyageWorldManager manager, NamespacedKey key) {
        return new ManagedWorldImpl(
                key,
                manager,
                false,
                World.Environment.NORMAL,
                1L,
                null,
                null,
                WorldType.NORMAL,
                false,
                Map.of(),
                true
        );
    }

    static class TestManager extends VoyageWorldManager {
        boolean unloadResult = true;
        boolean deleteDirectoryFailure = false;
        java.io.File deleteFolder;

        TestManager(VoyageContext context) {
            super(context);
        }

        @Override
        protected void saveState() {
            // Unit tests deliberately bypass delayed persistence; persistence is tested separately.
        }

        @Override
        public CompletableFuture<Boolean> unload(
                NamespacedKey worldKey,
                NamespacedKey fallbackKey,
                boolean save
        ) {
            return CompletableFuture.completedFuture(unloadResult);
        }

        @Override
        public java.io.File resolveWorldFolder(NamespacedKey key) {
            return deleteFolder != null ? deleteFolder : super.resolveWorldFolder(key);
        }

        @Override
        protected World loadBukkitWorld(ManagedWorldImpl world) {
            return null;
        }

        @Override
        public CompletableFuture<Void> deleteDirectory(java.io.File folder) {
            if (deleteDirectoryFailure) {
                return CompletableFuture.failedFuture(new java.io.IOException("synthetic delete failure"));
            }
            return CompletableFuture.completedFuture(null);
        }
    }
}