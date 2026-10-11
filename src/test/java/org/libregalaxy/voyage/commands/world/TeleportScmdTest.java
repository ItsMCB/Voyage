package org.libregalaxy.voyage.commands.world;

import com.mojang.brigadier.context.CommandContext;
import io.papermc.paper.command.brigadier.CommandSourceStack;
import io.papermc.paper.command.brigadier.argument.resolvers.selector.PlayerSelectorArgumentResolver;
import org.bukkit.Location;
import org.bukkit.NamespacedKey;
import org.bukkit.World;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.libregalaxy.voyage.VoyageContext;
import org.libregalaxy.voyage.api.VoyageAPI;
import org.libregalaxy.voyage.core.VoyageWorldManager;
import org.mockbukkit.mockbukkit.MockBukkit;
import org.mockbukkit.mockbukkit.ServerMock;
import org.mockbukkit.mockbukkit.entity.PlayerMock;

import java.util.Optional;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class TeleportScmdTest {

    private ServerMock server;
    private VoyageContext context;
    private VoyageWorldManager worldManager;
    private TeleportScmd command;

    @BeforeEach
    void setUp() {
        server = MockBukkit.mock();
        context = mock(VoyageContext.class);
        worldManager = mock(VoyageWorldManager.class);
        when(context.getWorldManager()).thenReturn(worldManager);
        command = new TeleportScmd(context);
    }

    @AfterEach
    void tearDown() {
        MockBukkit.unmock();
    }

    @Test
    void execute_defaultsToLogOffLocationWhenAvailable() {
        World targetWorld = server.addSimpleWorld("target-world");
        PlayerMock player = server.addPlayer();

        Location logOffLocation = new Location(targetWorld, 150.0, 72.0, -250.0, 90f, 0f);
        when(worldManager.getPlayerLogOffLocation(targetWorld.getKey(), player.getUniqueId()))
                .thenReturn(Optional.of(new VoyageAPI.OfflineLocation(
                        targetWorld.getKey(),
                        logOffLocation.getX(),
                        logOffLocation.getY(),
                        logOffLocation.getZ(),
                        logOffLocation.getYaw(),
                        logOffLocation.getPitch()
                )));

        @SuppressWarnings("unchecked")
        CommandContext<CommandSourceStack> ctx = mock(CommandContext.class);
        when(ctx.getArgument("world", NamespacedKey.class)).thenReturn(targetWorld.getKey());
        when(ctx.getArgument("player", PlayerSelectorArgumentResolver.class)).thenReturn(null);
        when(ctx.getArgument("strategy", String.class)).thenReturn(null);

        command.execute(ctx, player);

        assertEquals(logOffLocation, player.getLocation());
        String msg = player.nextMessage();
        assertNotNull(msg);
        assertTrue(msg.contains("last log-off location"));
    }

    @Test
    void execute_fallsBackToSpawnPointWhenNoLogOffLocation() {
        World targetWorld = server.addSimpleWorld("spawn-fallback-world");
        PlayerMock player = server.addPlayer();

        when(worldManager.getPlayerLogOffLocation(targetWorld.getKey(), player.getUniqueId()))
                .thenReturn(Optional.empty());

        @SuppressWarnings("unchecked")
        CommandContext<CommandSourceStack> ctx = mock(CommandContext.class);
        when(ctx.getArgument("world", NamespacedKey.class)).thenReturn(targetWorld.getKey());
        when(ctx.getArgument("player", PlayerSelectorArgumentResolver.class)).thenReturn(null);
        when(ctx.getArgument("strategy", String.class)).thenReturn(null);

        command.execute(ctx, player);

        assertEquals(targetWorld.getSpawnLocation(), player.getLocation());
        String fallbackWarning = player.nextMessage();
        assertNotNull(fallbackWarning);
        assertTrue(fallbackWarning.contains("Falling back to spawn point"));

        String successMsg = player.nextMessage();
        assertNotNull(successMsg);
        assertTrue(successMsg.contains("world spawn point"));
    }

    @Test
    void execute_teleportsToWorldSpawnWhenSpecified() {
        World targetWorld = server.addSimpleWorld("spawn-specified-world");
        PlayerMock player = server.addPlayer();

        // Even if a log-off location is present, specifying SPAWN_POINT must take precedence
        when(worldManager.getPlayerLogOffLocation(targetWorld.getKey(), player.getUniqueId()))
                .thenReturn(Optional.of(new VoyageAPI.OfflineLocation(
                        targetWorld.getKey(), 999.0, 70.0, 999.0, 0f, 0f
                )));

        @SuppressWarnings("unchecked")
        CommandContext<CommandSourceStack> ctx = mock(CommandContext.class);
        when(ctx.getArgument("world", NamespacedKey.class)).thenReturn(targetWorld.getKey());
        when(ctx.getArgument("player", PlayerSelectorArgumentResolver.class)).thenReturn(null);
        when(ctx.getArgument("strategy", String.class)).thenReturn("SPAWN_POINT");

        command.execute(ctx, player);

        assertEquals(targetWorld.getSpawnLocation(), player.getLocation());
        String successMsg = player.nextMessage();
        assertNotNull(successMsg);
        assertTrue(successMsg.contains("world spawn point"));
    }

    @Test
    void execute_doesNotTeleportToDifferentDimensionIfLogOffWorldMismatches() {
        World requestedWorld = server.addSimpleWorld("requested-world");
        World otherWorld = server.addSimpleWorld("other-world");
        PlayerMock player = server.addPlayer();
        player.teleport(otherWorld.getSpawnLocation());

        // Offline location reported from another world in the shared profile group
        when(worldManager.getPlayerLogOffLocation(requestedWorld.getKey(), player.getUniqueId()))
                .thenReturn(Optional.of(new VoyageAPI.OfflineLocation(
                        otherWorld.getKey(), 50.0, 70.0, 50.0, 0f, 0f
                )));

        @SuppressWarnings("unchecked")
        CommandContext<CommandSourceStack> ctx = mock(CommandContext.class);
        when(ctx.getArgument("world", NamespacedKey.class)).thenReturn(requestedWorld.getKey());
        when(ctx.getArgument("player", PlayerSelectorArgumentResolver.class)).thenReturn(null);
        when(ctx.getArgument("strategy", String.class)).thenReturn(null);

        command.execute(ctx, player);

        // Player must land in requestedWorld at spawn, NOT remain in otherWorld
        assertEquals(requestedWorld, player.getWorld());
        assertEquals(requestedWorld.getSpawnLocation(), player.getLocation());
    }
}