package org.libregalaxy.voyage.core;

import org.bukkit.Bukkit;
import org.bukkit.NamespacedKey;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.configuration.file.FileConfiguration;
import org.bukkit.plugin.java.JavaPlugin;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.libregalaxy.voyage.VoyageContext;
import org.mockito.MockedStatic;

import java.nio.file.Path;
import java.util.Collections;
import java.util.UUID;
import java.util.logging.Logger;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class PlayerProfileServiceTest {

    private VoyageContext mockContext;
    private JavaPlugin mockPlugin;

    @TempDir
    Path tempDir;

    @BeforeEach
    void setUp() {
        mockContext = mock(VoyageContext.class);
        mockPlugin = mock(JavaPlugin.class);
        when(mockContext.getPlugin()).thenReturn(mockPlugin);

        FileConfiguration mockConfig = mock(FileConfiguration.class);
        ConfigurationSection mockSection = mock(ConfigurationSection.class);
        lenient().when(mockPlugin.getConfig()).thenReturn(mockConfig);
        lenient().when(mockConfig.getConfigurationSection("world-groups")).thenReturn(mockSection);
        lenient().when(mockSection.getKeys(false)).thenReturn(Collections.emptySet());

        when(mockPlugin.getDataFolder()).thenReturn(tempDir.toFile());
        lenient().when(mockPlugin.getLogger()).thenReturn(mock(Logger.class));
    }

    private PlayerProfileService setupService() {
        return new PlayerProfileService(mockContext);
    }

    @Test
    void cloneWorldData_waitsForPendingWrites() throws Exception {
        PlayerProfileService service = setupService();

        UUID uuid = UUID.randomUUID();
        NamespacedKey sourceKey = NamespacedKey.fromString("custom:source");
        NamespacedKey targetKey = NamespacedKey.fromString("custom:target");

        try (MockedStatic<Bukkit> mockedBukkit = mockStatic(Bukkit.class)) {
            mockedBukkit.when(Bukkit::getOnlinePlayers).thenReturn(Collections.emptyList());

            // 1. Save an initial state synchronously
            PlayerStateSerializer.PlayerState initialState = new PlayerStateSerializer.PlayerState();
            initialState.health = 20.0;
            service.savePlayerStateSync(service.getProfileId(sourceKey), uuid, initialState);

            // 2. Update state and save ASYNC (queues on profileWriter thread)
            PlayerStateSerializer.PlayerState updatedState = new PlayerStateSerializer.PlayerState();
            updatedState.health = 10.0;
            service.savePlayerStateAsync(service.getProfileId(sourceKey), uuid, updatedState);

            // 3. Immediately call cloneWorldData (must wait for async write to finish)
            service.cloneWorldData(sourceKey, targetKey, null).join();
        }

        // 4. Verify cloned state reflects the updated value
        String targetProfileId = service.getProfileId(targetKey);
        PlayerStateSerializer.PlayerState clonedState = service.loadPlayerStateFromFile(targetProfileId, uuid);

        assertNotNull(clonedState, "Cloned player profile must be present in database");
        assertEquals(10.0, clonedState.health, "Cloned data must reflect the async pending write!");
    }
}