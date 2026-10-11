package org.libregalaxy.voyage.core;

import org.bukkit.Bukkit;
import org.bukkit.GameRule;
import org.bukkit.NamespacedKey;
import org.bukkit.World;
import org.bukkit.WorldType;
import org.bukkit.plugin.PluginManager;
import org.bukkit.scheduler.BukkitScheduler;
import org.junit.jupiter.api.Test;
import org.libregalaxy.voyage.api.events.VoyageWorldPreLoadEvent;

import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

class ManagedWorldImplLifecycleTest {
    private static final NamespacedKey KEY = NamespacedKey.fromString("custom:lifecycle");

    private ManagedWorldImpl world(VoyageWorldManager manager) {
        return new ManagedWorldImpl(
                KEY, manager, false, World.Environment.NORMAL, 123L,
                null, null, WorldType.NORMAL, false, Map.of(), true
        );
    }

    private static BukkitHarness bukkitHarness() {
        PluginManager pluginManager = mock(PluginManager.class);
        BukkitScheduler scheduler = mock(BukkitScheduler.class);
        AtomicReference<Runnable> scheduled = new AtomicReference<>();

        doAnswer(invocation -> {
            scheduled.set(invocation.getArgument(1));
            return null;
        }).when(scheduler).runTask(any(), any(Runnable.class));

        return new BukkitHarness(pluginManager, scheduler, scheduled);
    }

    @Test
    void load_whenAlreadyLoaded_returnsExistingWorldWithoutSchedulingAnotherLoad() {
        VoyageWorldManager manager = mock(VoyageWorldManager.class);
        World existing = mock(World.class);
        when(existing.getKey()).thenReturn(KEY);

        try (var bukkit = mockStatic(Bukkit.class)) {
            bukkit.when(() -> Bukkit.getWorld(KEY)).thenReturn(existing);

            ManagedWorldImpl managed = world(manager);
            assertSame(existing, managed.load().join());
            verifyNoInteractions(manager);
        }
    }

    @Test
    void load_whenPreLoadEventCancelled_failsAndDoesNotCreateWorld() {
        VoyageWorldManager manager = mock(VoyageWorldManager.class);
        when(manager.getPlugin()).thenReturn(mock(org.bukkit.plugin.java.JavaPlugin.class));
        BukkitHarness h = bukkitHarness();

        doAnswer(invocation -> {
            VoyageWorldPreLoadEvent event = invocation.getArgument(0);
            event.setCancelled(true);
            return null;
        }).when(h.pluginManager).callEvent(any(VoyageWorldPreLoadEvent.class));

        try (var bukkit = mockStatic(Bukkit.class)) {
            bukkit.when(() -> Bukkit.getWorld(KEY)).thenReturn(null);
            bukkit.when(Bukkit::getPluginManager).thenReturn(h.pluginManager);
            bukkit.when(Bukkit::getScheduler).thenReturn(h.scheduler);

            ManagedWorldImpl managed = world(manager);
            CompletableFuture<World> future = managed.load();
            assertNotNull(future);
            h.scheduled.get().run();

            CompletionException ex = assertThrows(CompletionException.class, future::join);
            assertTrue(ex.getCause().getMessage().toLowerCase().contains("cancel"));
            verify(manager, never()).loadBukkitWorld(any());
        }
    }

    @Test
    void load_whenWorldCreationReturnsNull_failsFuture() {
        VoyageWorldManager manager = mock(VoyageWorldManager.class);
        when(manager.getPlugin()).thenReturn(mock(org.bukkit.plugin.java.JavaPlugin.class));
        when(manager.loadBukkitWorld(any())).thenReturn(null);
        BukkitHarness h = bukkitHarness();

        try (var bukkit = mockStatic(Bukkit.class)) {
            bukkit.when(() -> Bukkit.getWorld(KEY)).thenReturn(null);
            bukkit.when(Bukkit::getPluginManager).thenReturn(h.pluginManager);
            bukkit.when(Bukkit::getScheduler).thenReturn(h.scheduler);

            ManagedWorldImpl managed = world(manager);
            CompletableFuture<World> future = managed.load();
            assertNotNull(future);
            h.scheduled.get().run();

            assertThrows(CompletionException.class, future::join);
            verify(manager).loadBukkitWorld(managed);
        }
    }

    @Test
    void configuredSpawn_isDefensivelyCopied() {
        VoyageWorldManager manager = mock(VoyageWorldManager.class);
        ManagedWorldImpl managed = world(manager);

        managed.setSpawnInternal(new int[]{1, 2, 3});
        int[] first = managed.getConfiguredSpawn().orElseThrow();
        first[0] = 999;

        assertArrayEquals(new int[]{1, 2, 3}, managed.getConfiguredSpawn().orElseThrow());
    }

    @Test
    @SuppressWarnings("deprecation")
    void configuredGameRules_areReturnedAsUnmodifiableSnapshot() {
        VoyageWorldManager manager = mock(VoyageWorldManager.class);
        ManagedWorldImpl managed = world(manager);

        managed.setGameRulesInternal(Map.of(GameRule.DO_DAYLIGHT_CYCLE, false));

        assertThrows(UnsupportedOperationException.class,
                () -> managed.getGameRules().put(GameRule.DO_DAYLIGHT_CYCLE, true));
    }

    private record BukkitHarness(PluginManager pluginManager, BukkitScheduler scheduler,
                                 AtomicReference<Runnable> scheduled) {}
}