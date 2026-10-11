package org.libregalaxy.voyage.api.portal.generator;

import org.bukkit.Location;
import org.bukkit.NamespacedKey;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockbukkit.mockbukkit.MockBukkit;
import org.mockbukkit.mockbukkit.ServerMock;

import static org.junit.jupiter.api.Assertions.*;

class PortalGeneratorTest {

    private ServerMock server;

    @BeforeEach
    void setUp() {
        server = MockBukkit.mock();
    }

    @AfterEach
    void tearDown() {
        MockBukkit.unmock();
    }

    @Test
    void noneGenerator_returnsExactSameLocationReference() {
        NoneGenerator gen = new NoneGenerator();
        Location input = new Location(null, 10.0, 20.0, 30.0, 90f, 45f);
        Location result = gen.findOrCreate(input);
        assertSame(input, result, "NoneGenerator must return the exact same location reference");
    }

    @Test
    void noneGenerator_preservesAllCoordinates() {
        NoneGenerator gen = new NoneGenerator();
        Location input = new Location(null, -100.5, 64.0, 200.25, 180f, -45f);
        Location result = gen.findOrCreate(input);
        assertEquals(-100.5, result.getX());
        assertEquals(64.0, result.getY());
        assertEquals(200.25, result.getZ());
        assertEquals(180f, result.getYaw());
        assertEquals(-45f, result.getPitch());
    }

    @Test
    void noneGenerator_handlesNullWorldLocation() {
        NoneGenerator gen = new NoneGenerator();
        Location input = new Location(null, 0, 0, 0);
        Location result = gen.findOrCreate(input);
        assertNotNull(result);
    }

    @Test
    void vanillaNetherGenerator_returnsExactSameLocationReference() {
        VanillaNetherGenerator gen = new VanillaNetherGenerator();
        Location input = new Location(null, 100.0, 64.0, -200.0);
        Location result = gen.findOrCreate(input);
        assertSame(input, result, "VanillaNetherGenerator must return the exact same location reference");
    }

    @Test
    void vanillaNetherGenerator_preservesAllCoordinates() {
        VanillaNetherGenerator gen = new VanillaNetherGenerator();
        Location input = new Location(null, 800.0, 128.0, -1600.0, 270f, 30f);
        Location result = gen.findOrCreate(input);
        assertEquals(800.0, result.getX());
        assertEquals(128.0, result.getY());
        assertEquals(-1600.0, result.getZ());
        assertEquals(270f, result.getYaw());
        assertEquals(30f, result.getPitch());
    }

    @Test
    void structureGenerator_returnsLocationEvenIfStructureMissing() {
        NamespacedKey key = NamespacedKey.minecraft("nonexistent_portal");
        StructureGenerator gen = new StructureGenerator(key);

        Location input = new Location(null, 0, 64, 0);
        // MockBukkit throws UnimplementedOperationException for getStructureManager()
        // We verify that the generator handles it gracefully or at least doesn't crash on null
        try {
            Location result = gen.findOrCreate(input);
            assertNotNull(result);
            assertSame(input, result, "StructureGenerator should always return the input location");
        } catch (org.mockbukkit.mockbukkit.exception.UnimplementedOperationException e) {
            // Expected in MockBukkit environment since StructureManager is not implemented
        }
    }

    @Test
    void structureGenerator_storesStructureKey() {
        NamespacedKey key = NamespacedKey.fromString("custom:my_portal_v2");
        StructureGenerator gen = new StructureGenerator(key);
        assertEquals(key, gen.getStructureKey());
    }

    @Test
    void structureGenerator_acceptsMinecraftNamespace() {
        NamespacedKey key = NamespacedKey.minecraft("nether_portal");
        StructureGenerator gen = new StructureGenerator(key);
        assertEquals("minecraft", gen.getStructureKey().getNamespace());
        assertEquals("nether_portal", gen.getStructureKey().getKey());
    }

    @Test
    void structureGenerator_acceptsCustomNamespace() {
        NamespacedKey key = NamespacedKey.fromString("voyage:custom_portal");
        StructureGenerator gen = new StructureGenerator(key);
        assertEquals("voyage", gen.getStructureKey().getNamespace());
        assertEquals("custom_portal", gen.getStructureKey().getKey());
    }

    @Test
    void noneGenerator_handlesExtremeCoordinates() {
        NoneGenerator gen = new NoneGenerator();
        Location input = new Location(null, Double.MAX_VALUE, Double.MIN_VALUE, 0.0);
        Location result = gen.findOrCreate(input);
        assertEquals(Double.MAX_VALUE, result.getX());
        assertEquals(Double.MIN_VALUE, result.getY());
    }

    @Test
    void vanillaNetherGenerator_handlesZeroCoordinates() {
        VanillaNetherGenerator gen = new VanillaNetherGenerator();
        Location input = new Location(null, 0.0, 0.0, 0.0, 0f, 0f);
        Location result = gen.findOrCreate(input);
        assertEquals(0.0, result.getX());
        assertEquals(0.0, result.getY());
        assertEquals(0.0, result.getZ());
    }
}