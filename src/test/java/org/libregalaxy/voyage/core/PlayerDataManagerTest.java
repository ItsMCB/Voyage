package org.libregalaxy.voyage.core;

import org.bukkit.Location;
import org.bukkit.NamespacedKey;
import org.bukkit.World;
import org.bukkit.attribute.Attribute;
import org.bukkit.entity.Player;
import org.bukkit.event.player.PlayerChangedWorldEvent;
import org.bukkit.event.player.PlayerTeleportEvent;
import org.bukkit.inventory.PlayerInventory;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.libregalaxy.voyage.VoyageContext;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;
import org.mockbukkit.mockbukkit.MockBukkit;
import org.mockbukkit.mockbukkit.ServerMock;

import java.util.Collections;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class PlayerDataManagerTest {

    private ServerMock server;
    private PlayerDataManager pdm;

    @Mock private VoyageContext mockContext;
    @Mock private PlayerProfileService mockProfileService;
    @Mock private PlayerStateSerializer mockStateSerializer;

    @BeforeEach
    void setUp() throws Exception {
        server = MockBukkit.mock();
        var plugin = MockBukkit.createMockPlugin("Voyage");
        lenient().when(mockContext.getPlugin()).thenReturn(plugin);
        lenient().when(mockContext.getService(PlayerStateSerializer.class)).thenReturn(mockStateSerializer);

        pdm = new PlayerDataManager(mockContext, mockStateSerializer);

        // Inject mock profile service via reflection to bypass internal instantiation
        java.lang.reflect.Field psField = PlayerDataManager.class.getDeclaredField("profileService");
        psField.setAccessible(true);
        psField.set(pdm, mockProfileService);
    }

    @AfterEach
    void tearDown() {
        MockBukkit.unmock();
    }

    @Test
    void onPlayerTeleport_nullToWorld_doesNotThrow() {
        Player player = mock(Player.class);
        UUID uuid = UUID.randomUUID();
        lenient().when(player.getUniqueId()).thenReturn(uuid); // Prevent NPE in ConcurrentHashMap

        World mockFromWorld = mock(World.class);
        lenient().when(mockFromWorld.getKey()).thenReturn(NamespacedKey.fromString("custom:from"));

        // Simulate a teleport event where the destination world is null
        PlayerTeleportEvent event = new PlayerTeleportEvent(
                player,
                new Location(mockFromWorld, 0, 0, 0),
                new Location(null, 10, 20, 30),
                PlayerTeleportEvent.TeleportCause.PLUGIN
        );

        // This should fail safely without throwing NullPointerException
        assertDoesNotThrow(() -> pdm.onPlayerTeleport(event));
    }

    @Test
    void onWorldChange_swapsProfilesCorrectly() {
        Player player = mock(Player.class);
        UUID uuid = UUID.randomUUID();
        lenient().when(player.getUniqueId()).thenReturn(uuid);
        lenient().when(player.getName()).thenReturn("TestPlayer");

        // Mock inventory and potion effects to prevent NPE in applyPlayerState when state is null
        PlayerInventory mockInventory = mock(PlayerInventory.class);
        lenient().when(player.getInventory()).thenReturn(mockInventory);
        lenient().when(player.getEnderChest()).thenReturn(mock(PlayerInventory.class));
        lenient().when(player.getActivePotionEffects()).thenReturn(Collections.emptyList());
        lenient().when(player.isDead()).thenReturn(false);
        lenient().when(player.getAttribute(Attribute.MAX_HEALTH)).thenReturn(null);

        World fromWorld = mock(World.class);
        World toWorld = mock(World.class);

        when(fromWorld.getKey()).thenReturn(NamespacedKey.fromString("custom:from"));
        when(toWorld.getKey()).thenReturn(NamespacedKey.fromString("custom:to"));

        when(player.getWorld()).thenReturn(toWorld);

        when(mockProfileService.getProfileId(fromWorld.getKey())).thenReturn("group:from_profile");
        when(mockProfileService.getProfileId(toWorld.getKey())).thenReturn("group:to_profile");

        when(mockProfileService.loadPlayerStateFromFile(eq("group:to_profile"), any())).thenReturn(null);

        PlayerChangedWorldEvent event = new PlayerChangedWorldEvent(player, fromWorld);

        assertDoesNotThrow(() -> pdm.onWorldChange(event));

        verify(mockStateSerializer).capturePlayerState(same(player), same(fromWorld), isNull());
    }
}