package org.libregalaxy.voyage.core;

import org.bukkit.Location;
import org.bukkit.NamespacedKey;
import org.bukkit.World;
import org.bukkit.entity.Player;
import org.bukkit.event.player.PlayerChangedWorldEvent;
import org.bukkit.event.player.PlayerTeleportEvent;
import org.bukkit.plugin.java.JavaPlugin;
import org.bukkit.configuration.file.FileConfiguration;
import org.bukkit.inventory.PlayerInventory;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.libregalaxy.voyage.VoyageContext;
import org.mockbukkit.mockbukkit.MockBukkit;
import org.mockbukkit.mockbukkit.ServerMock;

import java.lang.reflect.Field;
import java.util.Collections;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicBoolean;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.*;

class PlayerDataManagerThreadingTest {
    private ServerMock server;
    private VoyageContext context;
    private JavaPlugin plugin;
    private PlayerDataManager manager;
    private PlayerProfileService profileService;
    private PlayerStateSerializer serializer;

    @BeforeEach
    void setUp() throws Exception {
        server = MockBukkit.mock();
        plugin = MockBukkit.createMockPlugin("Voyage");

        context = mock(VoyageContext.class);
        serializer = mock(PlayerStateSerializer.class);
        profileService = mock(PlayerProfileService.class);

        when(context.getPlugin()).thenReturn(plugin);

        // Do not Mockito-stub methods on the real MockBukkit plugin. Use its real
        // configuration/data-folder implementation and replace only Voyage's service.
        FileConfiguration config = plugin.getConfig();
        if (config.getConfigurationSection("world-groups") == null) {
            config.createSection("world-groups");
        }

        manager = new PlayerDataManager(context, serializer);

        Field field = PlayerDataManager.class.getDeclaredField("profileService");
        field.setAccessible(true);
        field.set(manager, profileService);
    }

    @AfterEach
    void tearDown() {
        MockBukkit.unmock();
    }

    @Test
    void playerChangedWorld_doesNotPerformProfileDiskReadOnCallingThread() {
        Player player = mock(Player.class);
        World from = mock(World.class);
        World to = mock(World.class);
        UUID uuid = UUID.randomUUID();

        when(player.getUniqueId()).thenReturn(uuid);
        when(player.getWorld()).thenReturn(to);
        when(player.getInventory()).thenReturn(mock(PlayerInventory.class));
        when(player.getEnderChest()).thenReturn(mock(PlayerInventory.class));
        when(player.getActivePotionEffects()).thenReturn(Collections.emptyList());
        when(from.getKey()).thenReturn(NamespacedKey.fromString("custom:from"));
        when(to.getKey()).thenReturn(NamespacedKey.fromString("custom:to"));
        when(profileService.getProfileId(from.getKey())).thenReturn("group:from");
        when(profileService.getProfileId(to.getKey())).thenReturn("group:to");

        AtomicBoolean readCalled = new AtomicBoolean(false);
        when(profileService.loadPlayerStateFromFile(eq("group:to"), eq(uuid)))
                .thenAnswer(invocation -> {
                    readCalled.set(true);
                    return null;
                });

        manager.onWorldChange(new PlayerChangedWorldEvent(player, from));

        assertFalse(readCalled.get(),
                "PlayerChangedWorldEvent must not perform profile disk I/O synchronously");
    }

    @Test
    void pendingTeleportLocation_isCapturedOnlyForCrossWorldTeleport() throws Exception {
        Player player = mock(Player.class);
        UUID uuid = UUID.randomUUID();
        when(player.getUniqueId()).thenReturn(uuid);

        World from = mock(World.class);
        World sameWorld = from;
        World to = mock(World.class);

        Location sameWorldFrom = new Location(sameWorld, 1, 2, 3);
        Location sameWorldTo = new Location(sameWorld, 4, 5, 6);
        Location crossWorldFrom = new Location(from, 7, 8, 9);
        Location crossWorldTo = new Location(to, 10, 11, 12);

        manager.onPlayerTeleport(new PlayerTeleportEvent(
                player, sameWorldFrom, sameWorldTo,
                PlayerTeleportEvent.TeleportCause.PLUGIN));

        Map<UUID, Location> pending = pendingLocations();
        assertFalse(pending.containsKey(uuid),
                "Same-world teleports must not create a pending world-change location");

        manager.onPlayerTeleport(new PlayerTeleportEvent(
                player, crossWorldFrom, crossWorldTo,
                PlayerTeleportEvent.TeleportCause.PLUGIN));

        assertSame(crossWorldFrom, pending.get(uuid),
                "Cross-world teleports must capture the source location");
    }

    @SuppressWarnings("unchecked")
    private Map<UUID, Location> pendingLocations() throws Exception {
        Field field = PlayerDataManager.class.getDeclaredField("pendingLocations");
        field.setAccessible(true);
        return (Map<UUID, Location>) field.get(manager);
    }
}
