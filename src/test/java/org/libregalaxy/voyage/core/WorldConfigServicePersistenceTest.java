package org.libregalaxy.voyage.core;

import org.bukkit.Difficulty;
import org.bukkit.NamespacedKey;
import org.bukkit.World;
import org.bukkit.WorldType;
import org.bukkit.plugin.java.JavaPlugin;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.libregalaxy.voyage.VoyageContext;
import org.libregalaxy.voyage.api.ManagedWorld;
import org.libregalaxy.voyage.core.database.VoyageDatabaseService;
import org.mockbukkit.mockbukkit.MockBukkit;
import org.mockbukkit.mockbukkit.ServerMock;

import java.nio.file.Path;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.logging.Logger;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class WorldConfigServicePersistenceTest {

    @TempDir
    Path tempDir;

    private ServerMock server;
    private VoyageContext context;
    private JavaPlugin plugin;
    private VoyageDatabaseService databaseService;

    @BeforeEach
    void setUp() {
        server = MockBukkit.mock();
        context = mock(VoyageContext.class);
        plugin = mock(JavaPlugin.class);

        when(context.getPlugin()).thenReturn(plugin);
        when(plugin.getDataFolder()).thenReturn(tempDir.toFile());
        when(plugin.getLogger()).thenReturn(Logger.getLogger("WorldConfigTest"));

        databaseService = new VoyageDatabaseService(context);
        when(context.getDatabaseService()).thenReturn(databaseService);
    }

    @AfterEach
    void tearDown() {
        if (databaseService != null) {
            databaseService.shutdown();
        }
        MockBukkit.unmock();
    }

    @Test
    void loadState_preservesSpawnAnglesAndRegistersWorldSynchronouslyWithoutOverwritingDatabase() throws Exception {
        VoyageWorldManager manager = mock(VoyageWorldManager.class);
        Map<NamespacedKey, ManagedWorldImpl> registry = new ConcurrentHashMap<>();
        when(manager.getContext()).thenReturn(context);
        when(manager.getPlugin()).thenReturn(plugin);
        when(manager.getRegistry()).thenReturn(registry);
        when(manager.registerLoadedWorldFromDb(any())).thenAnswer(inv -> {
            ManagedWorldImpl mw = inv.getArgument(0);
            return registry.putIfAbsent(mw.getKey(), mw) == null;
        });

        WorldConfigService writerService = new WorldConfigService(plugin, manager, databaseService);

        NamespacedKey key = NamespacedKey.fromString("custom:angled_spawn");
        ManagedWorldImpl original = new ManagedWorldImpl(
                key, manager, false, World.Environment.NORMAL, 987654321L,
                null, null, WorldType.NORMAL, false, Map.of(), true
        );
        original.setDifficultyInternal(Difficulty.HARD);
        original.setSpawnInternal(new int[]{100, 75, -200});
        original.setSpawnAngleInternal(new float[]{135.5f, -25.0f});

        writerService.saveWorldSync(original);
        writerService.shutdown();

        // Simulate a fresh server boot reading from the existing SQLite database
        registry.clear();
        WorldConfigService bootReaderService = new WorldConfigService(plugin, manager, databaseService);
        bootReaderService.shutdown();

        // 1. World must be registered synchronously in the manager's registry
        ManagedWorld loaded = registry.get(key);
        assertNotNull(loaded, "World must be registered synchronously during loadState");
        assertEquals(Difficulty.HARD, loaded.getDifficulty());
        assertArrayEquals(new int[]{100, 75, -200}, loaded.getConfiguredSpawn().orElseThrow());
        assertArrayEquals(new float[]{135.5f, -25.0f}, loaded.getConfiguredSpawnAngles().orElseThrow(), 0.001f);

        // 2. A subsequent boot must STILL see the angles in SQLite (they were not overwritten with NULL)
        registry.clear();
        WorldConfigService secondBootService = new WorldConfigService(plugin, manager, databaseService);
        secondBootService.shutdown();

        ManagedWorld reloaded = registry.get(key);
        assertNotNull(reloaded);
        assertArrayEquals(new float[]{135.5f, -25.0f}, reloaded.getConfiguredSpawnAngles().orElseThrow(), 0.001f);
    }
}