package org.libregalaxy.voyage.core;

import net.kyori.adventure.text.Component;
import org.bukkit.Location;
import org.bukkit.NamespacedKey;
import org.bukkit.World;
import org.bukkit.attribute.Attribute;
import org.bukkit.entity.Player;
import org.bukkit.event.player.PlayerChangedWorldEvent;
import org.bukkit.event.player.PlayerJoinEvent;
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
import java.util.List;
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
        lenient().when(player.getUniqueId()).thenReturn(uuid);

        World mockFromWorld = mock(World.class);
        lenient().when(mockFromWorld.getKey()).thenReturn(NamespacedKey.fromString("custom:from"));

        PlayerTeleportEvent event = new PlayerTeleportEvent(
                player,
                new Location(mockFromWorld, 0, 0, 0),
                new Location(null, 10, 20, 30),
                PlayerTeleportEvent.TeleportCause.PLUGIN
        );

        assertDoesNotThrow(() -> pdm.onPlayerTeleport(event));
    }

    @Test
    void onWorldChange_swapsProfilesCorrectly() {
        Player player = mock(Player.class);
        UUID uuid = UUID.randomUUID();
        lenient().when(player.getUniqueId()).thenReturn(uuid);
        lenient().when(player.getName()).thenReturn("TestPlayer");

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

    @Test
    void onWorldChange_rapidChainedTransitions_doesNotSaveUnswappedIntermediateProfile() {
        Player player = mock(Player.class);
        UUID uuid = UUID.randomUUID();
        when(player.getUniqueId()).thenReturn(uuid);
        when(player.getName()).thenReturn("RapidTraveler");
        when(player.isOnline()).thenReturn(true);

        World worldA = mock(World.class);
        World worldB = mock(World.class);
        World worldC = mock(World.class);

        when(worldA.getKey()).thenReturn(NamespacedKey.fromString("custom:world_a"));
        when(worldB.getKey()).thenReturn(NamespacedKey.fromString("custom:world_b"));
        when(worldC.getKey()).thenReturn(NamespacedKey.fromString("custom:world_c"));

        when(mockProfileService.getProfileId(worldA.getKey())).thenReturn("isolated:custom_world_a");
        when(mockProfileService.getProfileId(worldB.getKey())).thenReturn("isolated:custom_world_b");
        when(mockProfileService.getProfileId(worldC.getKey())).thenReturn("isolated:custom_world_c");

        PlayerStateSerializer.PlayerState stateA = new PlayerStateSerializer.PlayerState();
        PlayerStateSerializer.PlayerState stateB = new PlayerStateSerializer.PlayerState();
        PlayerStateSerializer.PlayerState stateC = new PlayerStateSerializer.PlayerState();

        when(mockStateSerializer.capturePlayerState(eq(player), eq(worldA), any())).thenReturn(stateA);
        when(mockProfileService.loadPlayerStateFromFile("isolated:custom_world_b", uuid)).thenReturn(stateB);
        when(mockProfileService.loadPlayerStateFromFile("isolated:custom_world_c", uuid)).thenReturn(stateC);

        // 1. Transition A -> B begins (async load of B is queued, not yet applied on main thread)
        when(player.getWorld()).thenReturn(worldB);
        pdm.onWorldChange(new PlayerChangedWorldEvent(player, worldA));

        assertTrue(pdm.isSwitchingProfiles(uuid), "Player must be marked as switching profiles during A -> B");
        verify(mockStateSerializer, times(1)).capturePlayerState(same(player), same(worldA), any());

        // 2. Immediately transition B -> C BEFORE B's async load finishes on the main thread
        when(player.getWorld()).thenReturn(worldC);
        pdm.onWorldChange(new PlayerChangedWorldEvent(player, worldB));

        // CRITICAL: worldB must NEVER have its state captured because the player still holds worldA's inventory!
        verify(mockStateSerializer, never()).capturePlayerState(same(player), same(worldB), any());
        assertTrue(pdm.isSwitchingProfiles(uuid), "Player must remain in switchingProfiles during B -> C");

        // 3. Flush async and main-thread scheduler tasks
        server.getScheduler().waitAsyncTasksFinished();
        server.getScheduler().performOneTick();

        // Only stateC should have been applied, and switchingProfiles should now be cleared
        verify(mockStateSerializer, never()).applyPlayerState(same(player), any(), same(stateB), anyBoolean());
        verify(mockStateSerializer, times(1)).applyPlayerState(same(player), same(worldC), same(stateC), eq(true));
        assertFalse(pdm.isSwitchingProfiles(uuid), "switchingProfiles must be cleared after latest generation finishes");
    }

    @Test
    void onPlayerJoin_returningPlayerWithNullDatabaseState_doesNotClearInventoryOrTeleportToSpawn() {
        Player player = mock(Player.class);
        UUID uuid = UUID.randomUUID();
        when(player.getUniqueId()).thenReturn(uuid);
        when(player.getName()).thenReturn("ReturningVeteran");
        when(player.hasPlayedBefore()).thenReturn(true);
        when(player.isOnline()).thenReturn(true);

        World world = mock(World.class);
        when(world.getKey()).thenReturn(NamespacedKey.minecraft("overworld"));
        when(player.getWorld()).thenReturn(world);

        when(mockProfileService.getProfileId(world.getKey())).thenReturn("group:default_group");
        when(mockProfileService.loadPlayerStateFromFile("group:default_group", uuid)).thenReturn(null);

        pdm.onPlayerJoin(new PlayerJoinEvent(player, Component.empty()));

        server.getScheduler().waitAsyncTasksFinished();
        server.getScheduler().performOneTick();

        // Both clearIfNull and restoreLocation must be false when state is null on login
        verify(mockStateSerializer).applyPlayerState(same(player), same(world), isNull(), eq(false), eq(false));
        assertFalse(pdm.isSwitchingProfiles(uuid));
    }
}