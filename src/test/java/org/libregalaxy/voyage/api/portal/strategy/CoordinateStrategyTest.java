package org.libregalaxy.voyage.api.portal.strategy;

import org.bukkit.Location;
import org.bukkit.World;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class CoordinateStrategyTest {

    @Test
    void testVanillaScalingStrategy_CalculatesCorrectly() {
        // Arrange
        World mockTargetWorld = mock(World.class);
        Location fromLocation = new Location(null, 100.0, 50.0, 200.0, 90f, 45f);
        VanillaScalingStrategy strategy = new VanillaScalingStrategy(8.0);

        // Act
        Location result = strategy.calculate(fromLocation, mockTargetWorld);

        // Assert
        assertEquals(800.0, result.getX(), "X should be scaled by 8.0");
        assertEquals(50.0, result.getY(), "Y should remain untouched");
        assertEquals(1600.0, result.getZ(), "Z should be scaled by 8.0");
        assertEquals(90f, result.getYaw(), "Yaw should be preserved");
        assertEquals(45f, result.getPitch(), "Pitch should be preserved");
        assertEquals(mockTargetWorld, result.getWorld(), "Target world should be assigned");
    }

    @Test
    void testVanillaScalingStrategy_ThrowsOnNegativeScale() {
        // Assert
        IllegalArgumentException ex = assertThrows(
                IllegalArgumentException.class,
                () -> new VanillaScalingStrategy(-1.0)
        );
        assertTrue(ex.getMessage().contains("positive number"), "Should throw on negative scale");

        assertThrows(
                IllegalArgumentException.class,
                () -> new VanillaScalingStrategy(0.0)
        );
    }

    @Test
    void testFixedSpawnStrategy_ReturnsTargetSpawn() {
        // Arrange
        World mockTargetWorld = mock(World.class);
        Location mockSpawn = new Location(mockTargetWorld, 10, 20, 30);
        when(mockTargetWorld.getSpawnLocation()).thenReturn(mockSpawn);

        Location fromLocation = new Location(null, 100, 100, 100);
        FixedSpawnStrategy strategy = new FixedSpawnStrategy();

        // Act
        Location result = strategy.calculate(fromLocation, mockTargetWorld);

        // Assert
        assertEquals(mockSpawn, result, "Should strictly return the target world's spawn location");
    }
}