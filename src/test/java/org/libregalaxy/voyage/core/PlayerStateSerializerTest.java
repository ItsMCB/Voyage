package org.libregalaxy.voyage.core;

import org.bukkit.Bukkit;
import org.bukkit.GameMode;
import org.bukkit.Location;
import org.bukkit.NamespacedKey;
import org.bukkit.World;
import org.bukkit.attribute.Attribute;
import org.bukkit.attribute.AttributeInstance;
import org.bukkit.configuration.file.FileConfiguration;
import org.bukkit.entity.Player;
import org.bukkit.inventory.PlayerInventory;
import org.bukkit.plugin.java.JavaPlugin;
import org.junit.jupiter.api.Test;
import org.libregalaxy.voyage.VoyageContext;
import org.mockito.MockedStatic;

import java.util.List;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class PlayerStateSerializerTest {

    @Test
    void capture_usesExactLocationWhenItBelongsToTargetWorld() {
        VoyageContext context = mock(VoyageContext.class);
        Player player = mock(Player.class);
        when(player.getUniqueId()).thenReturn(UUID.randomUUID());
        World target = mock(World.class);
        NamespacedKey targetKey = NamespacedKey.fromString("custom:target");
        when(target.getKey()).thenReturn(targetKey);
        when(target.getSpawnLocation()).thenReturn(new Location(target, 0, 64, 0));

        PlayerInventory inventory = emptyInventory();
        PlayerInventory enderChest = emptyInventory();
        when(player.getInventory()).thenReturn(inventory);
        when(player.getEnderChest()).thenReturn(enderChest);
        when(player.getActivePotionEffects()).thenReturn(List.of());
        when(player.getRespawnLocation()).thenReturn(null);
        when(player.getAttribute(Attribute.MAX_HEALTH)).thenReturn(null);
        when(player.getHealth()).thenReturn(20.0);
        when(player.getLevel()).thenReturn(3);
        when(player.getExp()).thenReturn(0.25f);
        when(player.getTotalExperience()).thenReturn(100);
        when(player.getGameMode()).thenReturn(GameMode.SURVIVAL);
        when(player.getFoodLevel()).thenReturn(20);
        when(player.getSaturation()).thenReturn(5.0f);
        when(player.getFireTicks()).thenReturn(0);
        when(player.getFreezeTicks()).thenReturn(0);
        when(player.getFallDistance()).thenReturn(0.0f);
        when(player.getRemainingAir()).thenReturn(300);
        when(player.getMaximumAir()).thenReturn(300);

        Location exact = new Location(target, 10, 70, -4, 90, 20);

        PlayerStateSerializer serializer = new PlayerStateSerializer(context);
        PlayerStateSerializer.PlayerState state =
                serializer.capturePlayerState(player, target, exact);

        assertEquals("custom:target", state.lastLocWorld);
        assertEquals(10.0, state.lastLocX);
        assertEquals(70.0, state.lastLocY);
        assertEquals(-4.0, state.lastLocZ);
        assertEquals(90.0f, state.lastLocYaw);
        assertEquals(20.0f, state.lastLocPitch);
    }

    @Test
    void capture_fallsBackToTargetSpawnWhenExactLocationBelongsElsewhere() {
        VoyageContext context = mock(VoyageContext.class);
        Player player = mock(Player.class);
        when(player.getUniqueId()).thenReturn(UUID.randomUUID());
        World target = mock(World.class);
        World other = mock(World.class);

        when(target.getKey()).thenReturn(NamespacedKey.fromString("custom:target"));
        when(target.getSpawnLocation()).thenReturn(new Location(target, 100, 65, 200, 45, 10));

        PlayerInventory inventory = emptyInventory();
        PlayerInventory enderChest = emptyInventory();
        when(player.getInventory()).thenReturn(inventory);
        when(player.getEnderChest()).thenReturn(enderChest);
        when(player.getActivePotionEffects()).thenReturn(List.of());
        when(player.getRespawnLocation()).thenReturn(null);
        when(player.getAttribute(Attribute.MAX_HEALTH)).thenReturn(null);
        when(player.getHealth()).thenReturn(20.0);
        when(player.getGameMode()).thenReturn(GameMode.SURVIVAL);
        when(player.getFoodLevel()).thenReturn(20);
        when(player.getSaturation()).thenReturn(5.0f);
        when(player.getRemainingAir()).thenReturn(300);
        when(player.getMaximumAir()).thenReturn(300);
        when(player.getLevel()).thenReturn(0);
        when(player.getExp()).thenReturn(0.0f);
        when(player.getTotalExperience()).thenReturn(0);
        when(player.getFireTicks()).thenReturn(0);
        when(player.getFreezeTicks()).thenReturn(0);
        when(player.getFallDistance()).thenReturn(0.0f);

        Location exact = new Location(other, 1, 2, 3);

        PlayerStateSerializer.PlayerState state =
                new PlayerStateSerializer(context).capturePlayerState(player, target, exact);

        assertEquals("custom:target", state.lastLocWorld);
        assertEquals(100.0, state.lastLocX);
        assertEquals(65.0, state.lastLocY);
        assertEquals(200.0, state.lastLocZ);
    }

    @Test
    void capture_clampsHealthToMaximumHealth() {
        VoyageContext context = mock(VoyageContext.class);
        Player player = mock(Player.class);
        when(player.getUniqueId()).thenReturn(UUID.randomUUID());
        World target = mock(World.class);
        AttributeInstance maxHealth = mock(AttributeInstance.class);

        when(target.getKey()).thenReturn(NamespacedKey.fromString("custom:target"));
        when(target.getSpawnLocation()).thenReturn(new Location(target, 0, 64, 0));
        PlayerInventory inventory = emptyInventory();
        PlayerInventory enderChest = emptyInventory();
        when(player.getInventory()).thenReturn(inventory);
        when(player.getEnderChest()).thenReturn(enderChest);
        when(player.getActivePotionEffects()).thenReturn(List.of());
        when(player.getRespawnLocation()).thenReturn(null);
        when(player.getAttribute(Attribute.MAX_HEALTH)).thenReturn(maxHealth);
        when(maxHealth.getValue()).thenReturn(20.0);
        when(player.getHealth()).thenReturn(40.0);
        when(player.getLocation()).thenReturn(new Location(target, 0, 64, 0));
        when(player.getGameMode()).thenReturn(GameMode.SURVIVAL);
        when(player.getFoodLevel()).thenReturn(20);
        when(player.getSaturation()).thenReturn(5.0f);
        when(player.getRemainingAir()).thenReturn(300);
        when(player.getMaximumAir()).thenReturn(300);
        when(player.getFireTicks()).thenReturn(0);
        when(player.getFreezeTicks()).thenReturn(0);
        when(player.getFallDistance()).thenReturn(0.0f);

        PlayerStateSerializer.PlayerState state =
                new PlayerStateSerializer(context).capturePlayerState(player, target, null);

        assertEquals(20.0, state.maxHealth);
        assertEquals(20.0, state.health);
    }

    @Test
    void applyPlayerState_nullState_withStartFresh_clearsInventoryAndStatsAndSaves() {
        VoyageContext context = mock(VoyageContext.class);
        JavaPlugin plugin = mock(JavaPlugin.class);
        FileConfiguration config = mock(FileConfiguration.class);
        PlayerDataManager pdm = mock(PlayerDataManager.class);

        when(context.getPlugin()).thenReturn(plugin);
        when(plugin.getConfig()).thenReturn(config);
        when(context.getService(PlayerDataManager.class)).thenReturn(pdm);
        when(config.getBoolean("profiles.start-fresh-on-first-join", true)).thenReturn(true);

        Player player = mock(Player.class);
        when(player.getUniqueId()).thenReturn(UUID.randomUUID());
        World world = mock(World.class);
        PlayerInventory inventory = emptyInventory();
        PlayerInventory enderChest = emptyInventory();
        AttributeInstance maxHealthAttr = mock(AttributeInstance.class);

        when(player.getInventory()).thenReturn(inventory);
        when(player.getEnderChest()).thenReturn(enderChest);
        when(player.getAttribute(Attribute.MAX_HEALTH)).thenReturn(maxHealthAttr);
        when(player.getActivePotionEffects()).thenReturn(List.of());
        when(player.isDead()).thenReturn(false);
        when(player.getMaximumAir()).thenReturn(300);

        try (MockedStatic<Bukkit> bukkit = mockStatic(Bukkit.class)) {
            bukkit.when(Bukkit::getDefaultGameMode).thenReturn(GameMode.SURVIVAL);

            PlayerStateSerializer serializer = new PlayerStateSerializer(context);
            serializer.applyPlayerState(player, world, null, true);

            verify(inventory).clear();
            verify(enderChest).clear();
            verify(maxHealthAttr).setBaseValue(20.0);
            verify(player).setHealth(20.0);
            verify(player).setFoodLevel(20);
            verify(player).setLevel(0);
            verify(player).setExp(0.0f);
            verify(player).setFireTicks(0);
            verify(player).setFreezeTicks(0);
            verify(player).setFallDistance(0.0f);
            verify(player).setRemainingAir(300);
            verify(player).setRespawnLocation(null, true);
            verify(player, never()).setGameMode(GameMode.SURVIVAL);
            verify(player, never()).setGameMode(any());
            verify(pdm).savePlayerStateAsync(player);
        }
    }

    @Test
    void applyPlayerState_nullState_withoutStartFresh_preservesInventoryAndSaves() {
        VoyageContext context = mock(VoyageContext.class);
        JavaPlugin plugin = mock(JavaPlugin.class);
        FileConfiguration config = mock(FileConfiguration.class);
        PlayerDataManager pdm = mock(PlayerDataManager.class);

        when(context.getPlugin()).thenReturn(plugin);
        when(plugin.getConfig()).thenReturn(config);
        when(context.getService(PlayerDataManager.class)).thenReturn(pdm);
        when(config.getBoolean("profiles.start-fresh-on-first-join", true)).thenReturn(false);

        Player player = mock(Player.class);
        World world = mock(World.class);
        PlayerInventory inventory = emptyInventory();

        when(player.getInventory()).thenReturn(inventory);

        PlayerStateSerializer serializer = new PlayerStateSerializer(context);
        serializer.applyPlayerState(player, world, null, true);

        verify(inventory, never()).clear();
        verify(pdm).savePlayerStateAsync(player);
    }

    @Test
    void applyPlayerState_nullState_clearIfNullFalse_neverWipesEvenWithStartFresh() {
        VoyageContext context = mock(VoyageContext.class);
        JavaPlugin plugin = mock(JavaPlugin.class);
        FileConfiguration config = mock(FileConfiguration.class);
        PlayerDataManager pdm = mock(PlayerDataManager.class);

        when(context.getPlugin()).thenReturn(plugin);
        when(plugin.getConfig()).thenReturn(config);
        when(context.getService(PlayerDataManager.class)).thenReturn(pdm);
        when(config.getBoolean("profiles.start-fresh-on-first-join", true)).thenReturn(true);

        Player player = mock(Player.class);
        World world = mock(World.class);
        PlayerInventory inventory = emptyInventory();

        when(player.getInventory()).thenReturn(inventory);

        PlayerStateSerializer serializer = new PlayerStateSerializer(context);
        serializer.applyPlayerState(player, world, null, false);

        verify(inventory, never()).clear();
        verify(pdm).savePlayerStateAsync(player);
    }

    @Test
    void applyPlayerState_nullState_withRestoreLocation_savesTargetSpawnInsteadOfPreTeleportLocation() {
        VoyageContext context = mock(VoyageContext.class);
        JavaPlugin plugin = mock(JavaPlugin.class);
        FileConfiguration config = mock(FileConfiguration.class);
        PlayerDataManager pdm = mock(PlayerDataManager.class);

        when(context.getPlugin()).thenReturn(plugin);
        when(plugin.getConfig()).thenReturn(config);
        when(context.getService(PlayerDataManager.class)).thenReturn(pdm);
        when(config.getBoolean("profiles.start-fresh-on-first-join", true)).thenReturn(false);

        Player player = mock(Player.class);
        World world = mock(World.class);
        when(world.getSpawnLocation()).thenReturn(new Location(world, 10, 64, 20, 90f, 0f));

        PlayerStateSerializer serializer = new PlayerStateSerializer(context);
        serializer.applyPlayerState(player, world, null, false, true);

        Location expectedSpawn = new Location(world, 10.5, 64.0, 20.5, 90f, 0f);
        verify(player).teleportAsync(expectedSpawn);
        verify(pdm).savePlayerState(same(player), same(world), eq(true), eq(expectedSpawn));
    }

    @Test
    void respawnLocationInUnloadedWorld_isPreservedAcrossApplyAndCapture() {
        VoyageContext context = mock(VoyageContext.class);
        Player player = mock(Player.class);
        UUID uuid = UUID.randomUUID();
        when(player.getUniqueId()).thenReturn(uuid);

        World activeWorld = mock(World.class);
        when(activeWorld.getKey()).thenReturn(NamespacedKey.minecraft("overworld"));
        when(activeWorld.getSpawnLocation()).thenReturn(new Location(activeWorld, 0, 64, 0));

        PlayerInventory inventory = emptyInventory();
        PlayerInventory enderChest = emptyInventory();
        when(player.getInventory()).thenReturn(inventory);
        when(player.getEnderChest()).thenReturn(enderChest);
        when(player.getActivePotionEffects()).thenReturn(List.of());
        when(player.isDead()).thenReturn(false);
        when(player.getAttribute(Attribute.MAX_HEALTH)).thenReturn(null);
        when(player.getHealth()).thenReturn(20.0);
        when(player.getGameMode()).thenReturn(GameMode.SURVIVAL);
        when(player.getFoodLevel()).thenReturn(20);
        when(player.getSaturation()).thenReturn(5.0f);
        when(player.getRemainingAir()).thenReturn(300);
        when(player.getMaximumAir()).thenReturn(300);
        when(player.getLocation()).thenReturn(new Location(activeWorld, 0, 64, 0));
        when(player.getRespawnLocation()).thenReturn(null);

        PlayerStateSerializer.PlayerState savedState = new PlayerStateSerializer.PlayerState();
        savedState.health = 20.0;
        savedState.respawnWorld = "custom:evicted_home";
        savedState.respawnX = 120.5;
        savedState.respawnY = 70.0;
        savedState.respawnZ = -45.5;
        savedState.respawnYaw = 180.0f;
        savedState.respawnPitch = 0.0f;

        NamespacedKey evictedKey = NamespacedKey.fromString("custom:evicted_home");

        try (MockedStatic<Bukkit> bukkit = mockStatic(Bukkit.class)) {
            // Simulate custom:evicted_home being unloaded due to idle eviction
            bukkit.when(() -> Bukkit.getWorld(evictedKey)).thenReturn(null);

            PlayerStateSerializer serializer = new PlayerStateSerializer(context);
            serializer.applyPlayerState(player, activeWorld, savedState, false);

            PlayerStateSerializer.PlayerState recaptured = serializer.capturePlayerState(player, activeWorld, null);

            assertEquals("custom:evicted_home", recaptured.respawnWorld);
            assertEquals(120.5, recaptured.respawnX);
            assertEquals(70.0, recaptured.respawnY);
            assertEquals(-45.5, recaptured.respawnZ);
            assertEquals(180.0f, recaptured.respawnYaw);
        }
    }

    private static PlayerInventory emptyInventory() {
        PlayerInventory inventory = mock(PlayerInventory.class);
        when(inventory.getContents()).thenReturn(new org.bukkit.inventory.ItemStack[41]);
        return inventory;
    }

    @Test
    void playerState_defaults_areSafe() {
        PlayerStateSerializer.PlayerState state =
                new PlayerStateSerializer.PlayerState();

        assertEquals(1, state.schemaVersion);
        assertEquals(20, state.foodLevel);
        assertEquals(5.0f, state.saturation);
    }
}