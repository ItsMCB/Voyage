package org.libregalaxy.voyage.core;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
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

import java.io.File;
import java.io.InputStreamReader;
import java.io.FileInputStream;
import java.nio.charset.StandardCharsets;
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

        // Mock config to prevent NPEs during profile resolution
        FileConfiguration mockConfig = mock(FileConfiguration.class);
        ConfigurationSection mockSection = mock(ConfigurationSection.class);
        lenient().when(mockPlugin.getConfig()).thenReturn(mockConfig);
        lenient().when(mockConfig.getConfigurationSection("world-groups")).thenReturn(mockSection);
        lenient().when(mockSection.getKeys(false)).thenReturn(Collections.emptySet());

        // Mock data folder to use JUnit's TempDir
        when(mockPlugin.getDataFolder()).thenReturn(tempDir.toFile());

        // Mock logger
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

            // 1. Save an initial state to disk synchronously
            PlayerStateSerializer.PlayerState initialState = new PlayerStateSerializer.PlayerState();
            initialState.health = 20.0;
            service.savePlayerStateSync(service.getProfileId(sourceKey), uuid, initialState);

            // 2. Update state and save ASYNC (queues on profileWriter thread)
            PlayerStateSerializer.PlayerState updatedState = new PlayerStateSerializer.PlayerState();
            updatedState.health = 10.0;
            service.savePlayerStateAsync(service.getProfileId(sourceKey), uuid, updatedState);

            // 3. Immediately call cloneWorldData (This must wait for the async write to finish)
            service.cloneWorldData(sourceKey, targetKey, null).join();
        }

        // 4. Read the cloned file from the target profile
        String targetProfileId = service.getProfileId(targetKey);
        // FIX: Include 'playerdata' in the path to match PlayerProfileService's internal dataFolder structure
        File targetDir = new File(tempDir.toFile(), "playerdata/profiles" + File.separator + targetProfileId.replace(":", "_"));
        File targetFile = new File(targetDir, uuid.toString() + ".json");

        assertTrue(targetFile.exists(), "Target file should be created by clone operation");

        Gson gson = new GsonBuilder().setPrettyPrinting().create();
        try (InputStreamReader reader = new InputStreamReader(new FileInputStream(targetFile), StandardCharsets.UTF_8)) {
            PlayerStateSerializer.PlayerState clonedState = gson.fromJson(reader, PlayerStateSerializer.PlayerState.class);

            // 5. Assert the cloned data is the UPDATED state, not the old disk state
            assertEquals(10.0, clonedState.health, "Cloned data must reflect the async pending write!");
        }
    }
}