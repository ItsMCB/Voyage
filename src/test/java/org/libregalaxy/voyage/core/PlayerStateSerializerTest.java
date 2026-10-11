package org.libregalaxy.voyage.core;

import org.bukkit.Bukkit;
import org.bukkit.GameMode;
import org.bukkit.Location;
import org.bukkit.NamespacedKey;
import org.bukkit.World;
import org.bukkit.attribute.Attribute;
import org.bukkit.attribute.AttributeInstance;
import org.bukkit.entity.Player;
import org.bukkit.inventory.PlayerInventory;
import org.bukkit.plugin.java.JavaPlugin;
import org.bukkit.configuration.file.FileConfiguration;
import org.junit.jupiter.api.Test;
import org.libregalaxy.voyage.VoyageContext;
import org.mockito.MockedStatic;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class PlayerStateSerializerTest {

    @Test
    void capture_usesExactLocationWhenItBelongsToTargetWorld() {
        VoyageContext context = mock(VoyageContext.class);
        Player player = mock(Player.class);
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