package org.libregalaxy.voyage.core;

import net.kyori.adventure.nbt.ByteBinaryTag;
import net.kyori.adventure.nbt.CompoundBinaryTag;
import net.kyori.adventure.nbt.IntBinaryTag;
import net.kyori.adventure.nbt.StringBinaryTag;
import org.bukkit.GameRule;
import org.bukkit.GameRules;
import org.bukkit.NamespacedKey;
import org.bukkit.World;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockbukkit.mockbukkit.MockBukkit;
import org.mockbukkit.mockbukkit.ServerMock;

import java.lang.reflect.Method;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class WorldImportServiceGameruleTest {

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
    void legacyGamerulesAndBinaryTags_parsedCorrectly() throws Exception {
        WorldImportService service = new WorldImportService(null, null);
        ManagedWorldImpl mw = mock(ManagedWorldImpl.class);
        World world = mock(World.class);

        CompoundBinaryTag gamerulesNbt = CompoundBinaryTag.builder()
                // Binary byte (boolean in modern NBT) using 1.21.11 legacy name
                .put("doDaylightCycle", ByteBinaryTag.byteBinaryTag((byte) 1))
                // Binary byte using 1.21.11 legacy name
                .put("doMobSpawning", ByteBinaryTag.byteBinaryTag((byte) 0))
                // Binary integer
                .put("randomTickSpeed", IntBinaryTag.intBinaryTag(10))
                .build();

        Method method = WorldImportService.class.getDeclaredMethod("applyGameRules", ManagedWorldImpl.class, World.class, CompoundBinaryTag.class);
        method.setAccessible(true);
        method.invoke(service, mw, world, gamerulesNbt);

        // Verify modern GameRules.ADVANCE_TIME and SPAWN_MOBS were updated
        verify(world).setGameRule(GameRules.ADVANCE_TIME, true);
        verify(world).setGameRule(GameRules.SPAWN_MOBS, false);
    }
}