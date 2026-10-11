package org.libregalaxy.voyage.listeners;

import org.bukkit.Location;
import org.bukkit.NamespacedKey;
import org.bukkit.World;
import org.bukkit.event.player.PlayerPortalEvent;
import org.bukkit.event.player.PlayerTeleportEvent;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.plugin.java.JavaPlugin;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.libregalaxy.voyage.api.ManagedWorld;
import org.libregalaxy.voyage.api.VoyageAPI;
import org.libregalaxy.voyage.api.portal.PortalLink;
import org.libregalaxy.voyage.api.portal.PortalType;
import org.libregalaxy.voyage.api.portal.generator.NoneGenerator;
import org.libregalaxy.voyage.api.portal.generator.VanillaNetherGenerator;
import org.libregalaxy.voyage.api.portal.strategy.FixedSpawnStrategy;
import org.libregalaxy.voyage.api.portal.strategy.VanillaScalingStrategy;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockbukkit.mockbukkit.MockBukkit;
import org.mockbukkit.mockbukkit.ServerMock;
import org.mockbukkit.mockbukkit.entity.PlayerMock;

import java.util.Optional;
import java.util.concurrent.CompletableFuture;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class VoyagePortalListenerTest {

    private ServerMock server;
    private VoyagePortalListener listener;

    @Mock private VoyageAPI mockApi;
    @Mock private ManagedWorld mockCurrentWorld;
    @Mock private ManagedWorld mockTargetWorld;
    private JavaPlugin plugin;

    @BeforeEach
    void setUp() {
        server = MockBukkit.mock();
        plugin = MockBukkit.createMockPlugin("Voyage");
        listener = new VoyagePortalListener(mockApi, plugin);
    }

    @AfterEach
    void tearDown() {
        MockBukkit.unmock();
    }

    private World mockWorld(String namespace, String key) {
        World world = mock(World.class);
        lenient().when(world.getKey()).thenReturn(NamespacedKey.fromString(namespace + ":" + key));
        Location spawn = new Location(world, 0, 64, 0);
        lenient().when(world.getSpawnLocation()).thenReturn(spawn);
        return world;
    }

    @Test
    void onPortalEnter_nullFromWorld_doesNothing() {
        PlayerMock player = server.addPlayer();
        PlayerPortalEvent event = new PlayerPortalEvent(
                player, new Location(null, 0, 0, 0), null, PlayerTeleportEvent.TeleportCause.NETHER_PORTAL
        );
        assertDoesNotThrow(() -> listener.onPortalEnter(event));
    }

    @Test
    void onPortalEnter_nonPortalCause_doesNothing() {
        World world = mockWorld("minecraft", "test_world");
        PlayerMock player = server.addPlayer();
        Location loc = world.getSpawnLocation();

        PlayerPortalEvent event = new PlayerPortalEvent(
                player, loc, loc, PlayerTeleportEvent.TeleportCause.COMMAND
        );
        listener.onPortalEnter(event);
        assertFalse(event.isCancelled(), "Non-portal causes should not be cancelled");
    }

    @Test
    void onPortalEnter_noManagedWorld_forMinecraftNamespace_preservesVanilla() {
        World world = mockWorld("minecraft", "overworld");
        PlayerMock player = server.addPlayer();

        when(mockApi.getManagedWorld(world.getKey())).thenReturn(Optional.empty());

        PlayerPortalEvent event = new PlayerPortalEvent(
                player, world.getSpawnLocation(), null, PlayerTeleportEvent.TeleportCause.NETHER_PORTAL
        );
        listener.onPortalEnter(event);
        assertFalse(event.isCancelled(), "Vanilla minecraft worlds should preserve vanilla portal behavior");
    }

    @Test
    void onPortalEnter_noManagedWorld_forCustomNamespace_cancelsEvent() {
        World world = mockWorld("custom", "unmanaged_world");
        PlayerMock player = server.addPlayer();

        when(mockApi.getManagedWorld(world.getKey())).thenReturn(Optional.empty());

        PlayerPortalEvent event = new PlayerPortalEvent(
                player, world.getSpawnLocation(), null, PlayerTeleportEvent.TeleportCause.NETHER_PORTAL
        );
        listener.onPortalEnter(event);
        assertTrue(event.isCancelled(), "Custom namespace worlds without links should cancel portal events");
    }

    @Test
    void onPortalEnter_noLink_managedWorld_preservesVanillaForMinecraft() {
        World world = mockWorld("minecraft", "overworld");
        PlayerMock player = server.addPlayer();

        when(mockApi.getManagedWorld(world.getKey())).thenReturn(Optional.of(mockCurrentWorld));
        when(mockCurrentWorld.getPortalLink(PortalType.NETHER)).thenReturn(Optional.empty());

        PlayerPortalEvent event = new PlayerPortalEvent(
                player, world.getSpawnLocation(), null, PlayerTeleportEvent.TeleportCause.NETHER_PORTAL
        );
        listener.onPortalEnter(event);
        assertFalse(event.isCancelled());
    }

    @Test
    void onPortalEnter_linkExists_targetNotManaged_cancelsEvent() {
        World world = mockWorld("custom", "source");
        PlayerMock player = server.addPlayer();

        NamespacedKey targetKey = NamespacedKey.fromString("custom:unmanaged_target");
        PortalLink link = new PortalLink(targetKey, new FixedSpawnStrategy(), new NoneGenerator());

        when(mockApi.getManagedWorld(world.getKey())).thenReturn(Optional.of(mockCurrentWorld));
        when(mockCurrentWorld.getPortalLink(PortalType.NETHER)).thenReturn(Optional.of(link));
        when(mockApi.getManagedWorld(targetKey)).thenReturn(Optional.empty());

        PlayerPortalEvent event = new PlayerPortalEvent(
                player, world.getSpawnLocation(), null, PlayerTeleportEvent.TeleportCause.NETHER_PORTAL
        );
        listener.onPortalEnter(event);
        assertTrue(event.isCancelled(), "Should cancel when target world is not managed");
    }

    @Test
    void onPortalEnter_linkExists_targetLoaded_vanillaNetherGenerator_redirectsTo() {
        World sourceWorld = mockWorld("custom", "source2");
        World targetWorld = mockWorld("custom", "target2");
        PlayerMock player = server.addPlayer();

        NamespacedKey targetKey = targetWorld.getKey();
        PortalLink link = new PortalLink(targetKey, new VanillaScalingStrategy(8.0), new VanillaNetherGenerator());

        when(mockApi.getManagedWorld(sourceWorld.getKey())).thenReturn(Optional.of(mockCurrentWorld));
        when(mockCurrentWorld.getPortalLink(PortalType.NETHER)).thenReturn(Optional.of(link));
        when(mockApi.getManagedWorld(targetKey)).thenReturn(Optional.of(mockTargetWorld));
        when(mockTargetWorld.isLoaded()).thenReturn(true);
        when(mockTargetWorld.getBukkitWorld()).thenReturn(Optional.of(targetWorld));

        PlayerPortalEvent event = new PlayerPortalEvent(
                player, sourceWorld.getSpawnLocation(), null, PlayerTeleportEvent.TeleportCause.NETHER_PORTAL
        );
        listener.onPortalEnter(event);

        assertFalse(event.isCancelled(), "Should not cancel — vanilla nether generator delegates to Paper");
        assertNotNull(event.getTo(), "Should set destination location");
    }

    @Test
    void onPortalEnter_linkExists_targetLoaded_nonVanillaGenerator_cancelsAndTeleports() {
        World sourceWorld = mockWorld("custom", "source3");
        World targetWorld = mockWorld("custom", "target3");
        PlayerMock player = server.addPlayer();

        NamespacedKey targetKey = targetWorld.getKey();
        PortalLink link = new PortalLink(targetKey, new FixedSpawnStrategy(), new NoneGenerator());

        when(mockApi.getManagedWorld(sourceWorld.getKey())).thenReturn(Optional.of(mockCurrentWorld));
        when(mockCurrentWorld.getPortalLink(PortalType.NETHER)).thenReturn(Optional.of(link));
        when(mockApi.getManagedWorld(targetKey)).thenReturn(Optional.of(mockTargetWorld));
        when(mockTargetWorld.isLoaded()).thenReturn(true);
        when(mockTargetWorld.getBukkitWorld()).thenReturn(Optional.of(targetWorld));

        PlayerPortalEvent event = new PlayerPortalEvent(
                player, sourceWorld.getSpawnLocation(), null, PlayerTeleportEvent.TeleportCause.NETHER_PORTAL
        );
        listener.onPortalEnter(event);

        assertTrue(event.isCancelled(), "Should cancel — non-vanilla generator handles teleport directly");
    }

    @Test
    void onPortalEnter_endPortalCause_mapsToEndType() {
        World world = mockWorld("custom", "end_source");
        PlayerMock player = server.addPlayer();

        when(mockApi.getManagedWorld(world.getKey())).thenReturn(Optional.of(mockCurrentWorld));
        when(mockCurrentWorld.getPortalLink(PortalType.END)).thenReturn(Optional.empty());

        PlayerPortalEvent event = new PlayerPortalEvent(
                player, world.getSpawnLocation(), null, PlayerTeleportEvent.TeleportCause.END_PORTAL
        );
        listener.onPortalEnter(event);
        assertTrue(event.isCancelled(), "Custom namespace with no END link should cancel");
    }

    @Test
    void onPlayerQuit_removesFromLoadingSet() {
        PlayerMock player = server.addPlayer();
        PlayerQuitEvent event = new PlayerQuitEvent(player, net.kyori.adventure.text.Component.empty(), PlayerQuitEvent.QuitReason.DISCONNECTED);
        assertDoesNotThrow(() -> listener.onPlayerQuit(event));
    }

    @Test
    void onPortalEnter_targetNotLoaded_cancelsAndStartsAsyncLoad() {
        World sourceWorld = mockWorld("custom", "source_unloaded");
        PlayerMock player = server.addPlayer();

        NamespacedKey targetKey = NamespacedKey.fromString("custom:unloaded_target");
        PortalLink link = new PortalLink(targetKey, new FixedSpawnStrategy(), new NoneGenerator());

        when(mockApi.getManagedWorld(sourceWorld.getKey())).thenReturn(Optional.of(mockCurrentWorld));
        when(mockCurrentWorld.getPortalLink(PortalType.NETHER)).thenReturn(Optional.of(link));
        when(mockApi.getManagedWorld(targetKey)).thenReturn(Optional.of(mockTargetWorld));
        when(mockTargetWorld.isLoaded()).thenReturn(false);
        when(mockTargetWorld.load()).thenReturn(CompletableFuture.completedFuture(null));

        PlayerPortalEvent event = new PlayerPortalEvent(
                player, sourceWorld.getSpawnLocation(), null, PlayerTeleportEvent.TeleportCause.NETHER_PORTAL
        );
        listener.onPortalEnter(event);

        assertTrue(event.isCancelled(), "Should cancel while loading target world");
        assertTrue(player.getPortalCooldown() > 0, "Should set portal cooldown during async load");
    }
}