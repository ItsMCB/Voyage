package org.libregalaxy.voyage.core;

import org.bukkit.NamespacedKey;
import org.bukkit.World;
import org.bukkit.plugin.java.JavaPlugin;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.libregalaxy.voyage.VoyageContext;
import org.libregalaxy.voyage.api.ManagedWorld;
import org.mockbukkit.mockbukkit.MockBukkit;
import org.mockbukkit.mockbukkit.ServerMock;

import java.lang.reflect.Field;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class WorldEvictionControllerTest {

    private ServerMock server;
    private JavaPlugin plugin;
    private VoyageContext context;
    private VoyageWorldManager worldManager;
    private WorldEvictionController controller;

    @BeforeEach
    void setUp() {
        server = MockBukkit.mock();
        plugin = MockBukkit.createMockPlugin("Voyage");
        context = mock(VoyageContext.class);
        worldManager = mock(VoyageWorldManager.class);

        when(context.getPlugin()).thenReturn(plugin);
        controller = new WorldEvictionController(context, worldManager);
    }

    @AfterEach
    void tearDown() {
        controller.stop();
        MockBukkit.unmock();
    }

    @Test
    @SuppressWarnings("unchecked")
    void runEvictionCycle_retainsWorldOnFailedUnloadAndRemovesOnSuccess() throws Exception {
        NamespacedKey mainKey = NamespacedKey.minecraft("overworld");
        NamespacedKey idleKey = NamespacedKey.fromString("custom:idle_planet");

        World mainWorld = mock(World.class);
        when(mainWorld.getKey()).thenReturn(mainKey);

        World idleWorld = mock(World.class);
        when(idleWorld.getKey()).thenReturn(idleKey);
        when(idleWorld.getPlayers()).thenReturn(List.of());

        ManagedWorld managedIdle = mock(ManagedWorld.class);
        when(worldManager.getMainLevel()).thenReturn(mainWorld);
        when(worldManager.getManagedWorld(idleKey)).thenReturn(Optional.of(managedIdle));
        when(managedIdle.getBukkitWorld()).thenReturn(Optional.of(idleWorld));

        Field emptyField = WorldEvictionController.class.getDeclaredField("emptyWorlds");
        emptyField.setAccessible(true);
        Map<NamespacedKey, Long> emptyWorlds = (Map<NamespacedKey, Long>) emptyField.get(controller);

        // Simulate world having been empty for 10 minutes
        long tenMinutesAgo = System.currentTimeMillis() - TimeUnit.MINUTES.toMillis(10);
        emptyWorlds.put(idleKey, tenMinutesAgo);

        // 1. First eviction attempt fails (unload returns false)
        when(managedIdle.unload(true)).thenReturn(CompletableFuture.completedFuture(false));
        controller.runEvictionCycle();

        assertTrue(emptyWorlds.containsKey(idleKey),
                "World must remain tracked in emptyWorlds for retry when unload returns false");

        // 2. Second eviction attempt succeeds (unload returns true)
        emptyWorlds.put(idleKey, tenMinutesAgo);
        when(managedIdle.unload(true)).thenReturn(CompletableFuture.completedFuture(true));
        controller.runEvictionCycle();

        assertFalse(emptyWorlds.containsKey(idleKey),
                "World must be removed from emptyWorlds once unload succeeds");
    }
}