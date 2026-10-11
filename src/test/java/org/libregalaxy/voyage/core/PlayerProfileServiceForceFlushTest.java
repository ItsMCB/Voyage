package org.libregalaxy.voyage.core;

import org.bukkit.configuration.file.FileConfiguration;
import org.bukkit.plugin.java.JavaPlugin;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.libregalaxy.voyage.VoyageContext;
import org.mockbukkit.mockbukkit.MockBukkit;
import org.mockbukkit.mockbukkit.ServerMock;

import java.nio.file.Path;
import java.util.UUID;
import java.util.logging.Logger;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class PlayerProfileServiceForceFlushTest {

    @TempDir
    Path tempDir;

    private ServerMock server;
    private VoyageContext context;
    private JavaPlugin plugin;
    private PlayerProfileService service;

    @BeforeEach
    void setUp() {
        server = MockBukkit.mock();
        context = mock(VoyageContext.class);
        plugin = mock(JavaPlugin.class);

        FileConfiguration config = mock(FileConfiguration.class);
        when(context.getPlugin()).thenReturn(plugin);
        when(plugin.getDataFolder()).thenReturn(tempDir.toFile());
        when(plugin.getConfig()).thenReturn(config);
        when(config.getConfigurationSection("world-groups")).thenReturn(null);
        when(plugin.getLogger()).thenReturn(mock(Logger.class));

        service = new PlayerProfileService(context);
    }

    @AfterEach
    void tearDown() {
        service.shutdown();
        MockBukkit.unmock();
    }

    @Test
    void forceFlushPendingWrites_writesAllPendingStatesWithoutCrashing() {
        UUID uuid1 = UUID.randomUUID();
        UUID uuid2 = UUID.randomUUID();
        String profile = "isolated:custom_moon"; // Contains a colon, which used to break string parsing

        PlayerStateSerializer.PlayerState state1 = new PlayerStateSerializer.PlayerState();
        state1.health = 10.0;
        PlayerStateSerializer.PlayerState state2 = new PlayerStateSerializer.PlayerState();
        state2.health = 20.0;

        // Queue them up without processing
        service.savePlayerStateAsync(profile, uuid1, state1);
        service.savePlayerStateAsync(profile, uuid2, state2);

        // Force flush
        service.forceFlushPendingWrites();

        // Verify both were written to disk and cleared from pending
        assertEquals(10.0, service.loadPlayerStateFromFile(profile, uuid1).health);
        assertEquals(20.0, service.loadPlayerStateFromFile(profile, uuid2).health);
    }
}