package org.libregalaxy.voyage.core;

import org.bukkit.Bukkit;
import org.bukkit.NamespacedKey;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.plugin.PluginManager;
import org.bukkit.plugin.java.JavaPlugin;
import org.bukkit.scheduler.BukkitScheduler;
import org.mockbukkit.mockbukkit.MockBukkit;
import org.mockbukkit.mockbukkit.ServerMock;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.libregalaxy.voyage.VoyageContext;
import org.libregalaxy.voyage.api.VoyageAPI;
import org.libregalaxy.voyage.api.events.VoyageWorldPreLinkEvent;

import java.nio.file.Path;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.TimeUnit;
import java.util.function.DoubleConsumer;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

class PlayerProfileServiceMigrationTest {
    @TempDir Path tempDir;
    private ServerMock server;
    private VoyageContext context;
    private JavaPlugin plugin;
    private YamlConfiguration config;
    private PlayerProfileService service;

    @BeforeEach
    void setUp() {
        server = MockBukkit.mock();
        context = mock(VoyageContext.class);
        plugin = mock(JavaPlugin.class);
        config = new YamlConfiguration();
        config.createSection("world-groups");
        when(context.getPlugin()).thenReturn(plugin);
        when(plugin.getConfig()).thenReturn(config);
        when(plugin.getDataFolder()).thenReturn(tempDir.toFile());
        when(plugin.getLogger()).thenReturn(mock(java.util.logging.Logger.class));
        service = new PlayerProfileService(context);
    }

    @AfterEach
    void tearDown() { service.shutdown(); MockBukkit.unmock(); }

    private <T> T awaitWithTicks(java.util.concurrent.CompletableFuture<T> future) {
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5);
        while (!future.isDone() && System.nanoTime() < deadline) {
            server.getScheduler().performOneTick();
            Thread.yield();
        }
        assertTrue(future.isDone(), "Future did not complete within test timeout");
        return future.join();
    }

    @Test
    void setProfileId_movesWorldIntoTargetGroupAndPersistsMembership() throws Exception {
        NamespacedKey world = NamespacedKey.fromString("custom:alpha");
        config.set("world-groups.old", List.of(world.asString()));
        UUID uuid = UUID.randomUUID();
        service.savePlayerStateSync(service.getDefaultProfileId(world), uuid,
                new PlayerStateSerializer.PlayerState());

        awaitWithTicks(service.setProfileId(
                world, "survival", VoyageAPI.DataLinkMergeStrategy.SOURCE_PRIORITY, null));

        assertTrue(config.getStringList("world-groups.survival").contains(world.asString()));
        assertFalse(config.getStringList("world-groups.old").contains(world.asString()));
        verify(plugin).saveConfig();
    }

    @Test
    void setProfileId_destinationPriority_doesNotOverwriteExistingTargetFile() throws Exception {
        NamespacedKey world = NamespacedKey.fromString("custom:alpha");
        UUID uuid = UUID.randomUUID();
        PlayerStateSerializer.PlayerState sourceState = new PlayerStateSerializer.PlayerState();
        sourceState.health = 5.0;
        PlayerStateSerializer.PlayerState targetState = new PlayerStateSerializer.PlayerState();
        targetState.health = 19.0;

        service.savePlayerStateSync(service.getDefaultProfileId(world), uuid, sourceState);
        service.savePlayerStateSync("group:survival", uuid, targetState);

        awaitWithTicks(service.setProfileId(
                world, "survival", VoyageAPI.DataLinkMergeStrategy.DESTINATION_PRIORITY, null));

        assertEquals(19.0,
                service.loadPlayerStateFromFile("group:survival", uuid).health);
    }

    @Test
    void removeWorldDataLink_withoutClone_createsEmptyIsolatedProfile() throws Exception {
        NamespacedKey world = NamespacedKey.fromString("custom:alpha");
        UUID uuid = UUID.randomUUID();
        service.savePlayerStateSync("group:survival", uuid, new PlayerStateSerializer.PlayerState());
        config.set("world-groups.survival", List.of(world.asString()));

        awaitWithTicks(service.removeWorldDataLink(world, false, null));

        assertFalse(config.getStringList("world-groups.survival").contains(world.asString()));
        assertNull(service.loadPlayerStateFromFile(service.getDefaultProfileId(world), uuid));
    }

    @Test
    void progress_isMonotonicAndEndsAtOne() throws Exception {
        NamespacedKey world = NamespacedKey.fromString("custom:alpha");
        UUID a = UUID.randomUUID();
        UUID b = UUID.randomUUID();
        service.savePlayerStateSync(service.getDefaultProfileId(world), a, new PlayerStateSerializer.PlayerState());
        service.savePlayerStateSync(service.getDefaultProfileId(world), b, new PlayerStateSerializer.PlayerState());

        var progress = new java.util.concurrent.CopyOnWriteArrayList<Double>();
        awaitWithTicks(service.cloneWorldData(
                world, NamespacedKey.fromString("custom:beta"), progress::add));

        assertFalse(progress.isEmpty());
        assertEquals(1.0, progress.getLast());
        for (int i = 1; i < progress.size(); i++) assertTrue(progress.get(i) >= progress.get(i - 1));
    }
}
