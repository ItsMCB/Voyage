package org.libregalaxy.voyage.core;

import org.bukkit.NamespacedKey;
import org.bukkit.configuration.ConfigurationSection;
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
import java.util.Collections;
import java.util.UUID;
import java.util.concurrent.TimeUnit;
import java.util.logging.Logger;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class PlayerProfileServicePersistenceTest {

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
        ConfigurationSection groups = mock(ConfigurationSection.class);

        when(context.getPlugin()).thenReturn(plugin);
        when(plugin.getDataFolder()).thenReturn(tempDir.toFile());
        when(plugin.getConfig()).thenReturn(config);
        when(config.getConfigurationSection("world-groups")).thenReturn(groups);
        when(groups.getKeys(false)).thenReturn(Collections.emptySet());
        lenient().when(plugin.getLogger()).thenReturn(mock(Logger.class));

        service = new PlayerProfileService(context);
    }

    @AfterEach
    void tearDown() {
        service.shutdown();
        MockBukkit.unmock();
    }

    @Test
    void defaultProfileId_isStableAndNamespaced() {
        NamespacedKey key = NamespacedKey.fromString("custom:moon");

        assertEquals("isolated:custom_moon", service.getDefaultProfileId(key));
        assertEquals(service.getDefaultProfileId(key), service.getProfileId(key));
    }

    @Test
    void synchronousSave_thenLoad_roundTripsState() {
        UUID uuid = UUID.randomUUID();
        String profile = "isolated:custom_moon";

        PlayerStateSerializer.PlayerState state = new PlayerStateSerializer.PlayerState();
        state.health = 17.5;
        state.level = 12;
        state.lastLocWorld = "custom:moon";
        state.lastLocX = 12.25;

        service.savePlayerStateSync(profile, uuid, state);

        PlayerStateSerializer.PlayerState loaded =
                service.loadPlayerStateFromFile(profile, uuid);

        assertNotNull(loaded);
        assertEquals(17.5, loaded.health);
        assertEquals(12, loaded.level);
        assertEquals("custom:moon", loaded.lastLocWorld);
        assertEquals(12.25, loaded.lastLocX);
    }

    @Test
    void asyncSave_isVisibleToImmediateReadThroughPendingWriteCache() {
        UUID uuid = UUID.randomUUID();
        String profile = "isolated:custom_moon";

        PlayerStateSerializer.PlayerState state = new PlayerStateSerializer.PlayerState();
        state.health = 9.0;

        service.savePlayerStateAsync(profile, uuid, state);

        PlayerStateSerializer.PlayerState loaded =
                service.loadPlayerStateFromFile(profile, uuid);

        assertNotNull(loaded);
        assertEquals(9.0, loaded.health);
    }

    @Test
    void deleteWorldData_purgesProfileRecordsFromDatabase() throws Exception {
        UUID uuid = UUID.randomUUID();
        NamespacedKey key = NamespacedKey.fromString("custom:to_delete");
        String profile = service.getDefaultProfileId(key);

        PlayerStateSerializer.PlayerState state = new PlayerStateSerializer.PlayerState();
        state.health = 15.0;

        service.savePlayerStateSync(profile, uuid, state);
        assertNotNull(service.loadPlayerStateFromFile(profile, uuid));

        service.deleteWorldData(key);
        Thread.sleep(100);

        assertNull(service.loadPlayerStateFromFile(profile, uuid));
    }

    @Test
    void offlineLocation_readsStoredWorldAndCoordinates() {
        UUID uuid = UUID.randomUUID();
        String profile = "isolated:custom_moon";

        PlayerStateSerializer.PlayerState state = new PlayerStateSerializer.PlayerState();
        state.lastLocWorld = "custom:moon";
        state.lastLocX = 1.5;
        state.lastLocY = 72.0;
        state.lastLocZ = -4.25;
        state.lastLocYaw = 90.0f;
        state.lastLocPitch = 30.0f;

        service.savePlayerStateSync(profile, uuid, state);

        var location = service.getPlayerLogOffLocation(
                NamespacedKey.fromString("custom:moon"),
                uuid
        ).orElseThrow();

        assertEquals(NamespacedKey.fromString("custom:moon"), location.worldKey());
        assertEquals(1.5, location.x());
        assertEquals(72.0, location.y());
        assertEquals(-4.25, location.z());
        assertEquals(90.0f, location.yaw());
        assertEquals(30.0f, location.pitch());
    }

    @Test
    void cloneWorldData_waitsForPendingWriterAndCopiesNewestState() throws Exception {
        UUID uuid = UUID.randomUUID();
        NamespacedKey source = NamespacedKey.fromString("custom:source");
        NamespacedKey target = NamespacedKey.fromString("custom:target");

        PlayerStateSerializer.PlayerState initial = new PlayerStateSerializer.PlayerState();
        initial.health = 20.0;
        service.savePlayerStateSync(service.getDefaultProfileId(source), uuid, initial);

        PlayerStateSerializer.PlayerState updated = new PlayerStateSerializer.PlayerState();
        updated.health = 7.0;
        service.savePlayerStateAsync(service.getDefaultProfileId(source), uuid, updated);

        service.cloneWorldData(source, target, null).get(5, TimeUnit.SECONDS);

        PlayerStateSerializer.PlayerState cloned =
                service.loadPlayerStateFromFile(service.getDefaultProfileId(target), uuid);

        assertNotNull(cloned);
        assertEquals(7.0, cloned.health);
    }

    @Test
    void previewProfileMerge_countsNewConflictsAndTargetOnly() throws Exception {
        UUID a = UUID.randomUUID();
        UUID b = UUID.randomUUID();
        UUID c = UUID.randomUUID();

        NamespacedKey source = NamespacedKey.fromString("custom:source");
        String sourceProfile = service.getDefaultProfileId(source);
        String targetProfile = "group:shared";

        service.savePlayerStateSync(sourceProfile, a, new PlayerStateSerializer.PlayerState());
        service.savePlayerStateSync(sourceProfile, b, new PlayerStateSerializer.PlayerState());

        service.savePlayerStateSync(targetProfile, b, new PlayerStateSerializer.PlayerState());
        service.savePlayerStateSync(targetProfile, c, new PlayerStateSerializer.PlayerState());

        var preview = service.previewProfileMerge(
                source,
                "shared",
                org.libregalaxy.voyage.api.VoyageAPI.DataLinkMergeStrategy.SOURCE_PRIORITY
        );

        assertEquals(1, preview.newToTarget());
        assertEquals(1, preview.conflicts());
        assertEquals(1, preview.targetOnly());
    }
}