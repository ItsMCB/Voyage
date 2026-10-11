package org.libregalaxy.voyage.commands.voyage;

import org.bukkit.plugin.java.JavaPlugin;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.libregalaxy.voyage.VoyageContext;
import org.libregalaxy.voyage.core.PlayerDataManager;
import org.libregalaxy.voyage.core.PlayerStateSerializer;
import org.libregalaxy.voyage.core.VoyageWorldManager;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockbukkit.mockbukkit.MockBukkit;
import org.mockbukkit.mockbukkit.ServerMock;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class ReloadScmdTest {

    private ServerMock server;
    private JavaPlugin plugin;

    @Mock private VoyageContext mockContext;
    @Mock private VoyageWorldManager mockManager;
    @Mock private PlayerDataManager mockPdm;

    @BeforeEach
    void setUp() {
        server = MockBukkit.mock();
        plugin = MockBukkit.createMockPlugin("Voyage");
        lenient().when(mockContext.getPlugin()).thenReturn(plugin);
        lenient().when(mockContext.getWorldManager()).thenReturn(mockManager);
        lenient().when(mockContext.getPlayerDataManager()).thenReturn(mockPdm);
    }

    @AfterEach
    void tearDown() {
        MockBukkit.unmock();
    }

    @Test
    void reloadScmd_hasNoArguments() {
        ReloadScmd cmd = new ReloadScmd(mockContext);
        assertTrue(cmd.getArguments().isEmpty());
    }

    @Test
    void reloadScmd_hasCorrectName() {
        ReloadScmd cmd = new ReloadScmd(mockContext);
        assertEquals("reload", cmd.getName());
    }

    @Test
    void reloadScmd_hasCorrectPermission() {
        ReloadScmd cmd = new ReloadScmd(mockContext);
        assertEquals("voyage.admin", cmd.getPermission());
    }
}