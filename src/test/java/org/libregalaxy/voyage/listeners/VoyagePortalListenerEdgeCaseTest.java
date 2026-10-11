package org.libregalaxy.voyage.listeners;

import me.itsmcb.vexelcore.bukkit.api.framework.TextUtils;
import net.kyori.adventure.text.minimessage.tag.resolver.Placeholder;
import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.NamespacedKey;
import org.bukkit.World;
import org.bukkit.entity.Player;
import org.bukkit.event.player.PlayerPortalEvent;
import org.bukkit.event.player.PlayerTeleportEvent;
import org.bukkit.plugin.java.JavaPlugin;
import org.bukkit.scheduler.BukkitScheduler;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.libregalaxy.voyage.api.ManagedWorld;
import org.libregalaxy.voyage.api.VoyageAPI;
import org.libregalaxy.voyage.api.portal.PortalLink;
import org.libregalaxy.voyage.api.portal.PortalType;
import org.libregalaxy.voyage.api.portal.generator.NoneGenerator;
import org.libregalaxy.voyage.api.portal.generator.VanillaNetherGenerator;
import org.libregalaxy.voyage.api.portal.strategy.FixedSpawnStrategy;
import org.mockito.MockedStatic;
import org.mockbukkit.mockbukkit.MockBukkit;
import org.mockbukkit.mockbukkit.ServerMock;

import java.util.Optional;
import java.util.concurrent.CompletableFuture;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class VoyagePortalListenerEdgeCaseTest {

    private ServerMock server;
    private VoyageAPI api;
    private ManagedWorld current;
    private ManagedWorld target;
    private JavaPlugin plugin;
    private VoyagePortalListener listener;

    @BeforeEach
    void setUp() {
        server = MockBukkit.mock();
        plugin = MockBukkit.createMockPlugin("Voyage");

        api = mock(VoyageAPI.class);
        current = mock(ManagedWorld.class);
        target = mock(ManagedWorld.class);

        listener = new VoyagePortalListener(api, plugin);
    }

    @AfterEach
    void tearDown() {
        MockBukkit.unmock();
    }

    private World world(String key) {
        World world = mock(World.class);
        NamespacedKey nk = NamespacedKey.fromString(key);
        when(world.getKey()).thenReturn(nk);
        // FIX: Stub getSpawnLocation() so FixedSpawnStrategy does not return null
        when(world.getSpawnLocation()).thenReturn(new Location(world, 0, 64, 0));
        return world;
    }

    @Test
    void endPortal_withVanillaNetherGenerator_isNotTreatedAsNativeNetherPortal() {
        World source = world("custom:source");
        World destination = world("custom:end");

        Player player = mock(Player.class);
        when(player.getUniqueId()).thenReturn(java.util.UUID.randomUUID());
        when(player.isOnline()).thenReturn(true);
        when(player.teleportAsync(any(Location.class))).thenReturn(CompletableFuture.completedFuture(true));
        PortalLink link = new PortalLink(
                destination.getKey(),
                new FixedSpawnStrategy(),
                new VanillaNetherGenerator()
        );

        when(api.getManagedWorld(source.getKey())).thenReturn(Optional.of(current));
        when(current.getPortalLink(PortalType.END)).thenReturn(Optional.of(link));
        when(api.getManagedWorld(destination.getKey())).thenReturn(Optional.of(target));
        when(target.isLoaded()).thenReturn(true);
        when(target.getBukkitWorld()).thenReturn(Optional.of(destination));
        PlayerPortalEvent event = new PlayerPortalEvent(
                player,
                source.getSpawnLocation(),
                null,
                PlayerTeleportEvent.TeleportCause.END_PORTAL
        );

        listener.onPortalEnter(event);

        assertTrue(
                event.isCancelled(),
                "VanillaNetherGenerator must only activate Paper-native handling for NETHER portals"
        );
        verify(player).teleportAsync(any(Location.class));
    }

    @Test
    void repeatedPortalEntry_whileLoading_isCancelled() {
        World source = world("custom:source");
        World destination = world("custom:target");
        Player player = mock(Player.class);
        when(player.getUniqueId()).thenReturn(java.util.UUID.randomUUID());
        when(player.isOnline()).thenReturn(true);

        PortalLink link = new PortalLink(
                destination.getKey(),
                new FixedSpawnStrategy(),
                new NoneGenerator()
        );

        CompletableFuture<World> pendingLoad = new CompletableFuture<>();

        when(api.getManagedWorld(source.getKey())).thenReturn(Optional.of(current));
        when(current.getPortalLink(PortalType.NETHER)).thenReturn(Optional.of(link));
        when(api.getManagedWorld(destination.getKey())).thenReturn(Optional.of(target));
        when(target.isLoaded()).thenReturn(false);
        when(target.load()).thenReturn(pendingLoad);

        PlayerPortalEvent first = new PlayerPortalEvent(
                player, source.getSpawnLocation(), null,
                PlayerTeleportEvent.TeleportCause.NETHER_PORTAL
        );
        listener.onPortalEnter(first);

        assertTrue(first.isCancelled());

        PlayerPortalEvent second = new PlayerPortalEvent(
                player, source.getSpawnLocation(), null,
                PlayerTeleportEvent.TeleportCause.NETHER_PORTAL
        );
        listener.onPortalEnter(second);

        assertTrue(
                second.isCancelled(),
                "A player already waiting for a world load must not enqueue another teleport"
        );
        verify(target, times(1)).load();
    }

    @Test
    void failedDestinationLoad_clearsLoadingState() {
        World source = world("custom:source");
        World destination = world("custom:target");
        Player player = mock(Player.class);
        when(player.getUniqueId()).thenReturn(java.util.UUID.randomUUID());
        when(player.isOnline()).thenReturn(true);

        PortalLink link = new PortalLink(
                destination.getKey(),
                new FixedSpawnStrategy(),
                new NoneGenerator()
        );

        when(api.getManagedWorld(source.getKey())).thenReturn(Optional.of(current));
        when(current.getPortalLink(PortalType.NETHER)).thenReturn(Optional.of(link));
        when(api.getManagedWorld(destination.getKey())).thenReturn(Optional.of(target));
        when(target.isLoaded()).thenReturn(false);
        when(target.load()).thenReturn(
                CompletableFuture.failedFuture(new IllegalStateException("load failed"))
        );

        PlayerPortalEvent event = new PlayerPortalEvent(
                player, source.getSpawnLocation(), null,
                PlayerTeleportEvent.TeleportCause.NETHER_PORTAL
        );
        BukkitScheduler scheduler = mock(BukkitScheduler.class);
        doAnswer(inv -> { ((Runnable) inv.getArgument(1)).run(); return null; })
                .when(scheduler).runTask(any(), any(Runnable.class));

        try (MockedStatic<Bukkit> bukkit = mockStatic(Bukkit.class, CALLS_REAL_METHODS)) {
            bukkit.when(Bukkit::getScheduler).thenReturn(scheduler);

            listener.onPortalEnter(event);

            assertTrue(event.isCancelled());

            // After the failure callback has run, a later attempt should be allowed
            // to invoke load again rather than being permanently stuck.
            PlayerPortalEvent retry = new PlayerPortalEvent(
                    player, source.getSpawnLocation(), null,
                    PlayerTeleportEvent.TeleportCause.NETHER_PORTAL
            );
            listener.onPortalEnter(retry);
        }

        verify(target, atLeast(2)).load();
    }
}