package org.libregalaxy.voyage.commands.world;

import com.mojang.brigadier.context.CommandContext;
import io.papermc.paper.command.brigadier.CommandSourceStack;
import org.bukkit.NamespacedKey;
import org.bukkit.World;
import org.bukkit.WorldBorder;
import org.bukkit.entity.Player;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.libregalaxy.voyage.VoyageContext;
import org.mockbukkit.mockbukkit.MockBukkit;
import org.mockbukkit.mockbukkit.ServerMock;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class BorderScmdValidationTest {

    private ServerMock server;
    private VoyageContext context;
    private BorderScmd command;

    @BeforeEach
    void setUp() {
        server = MockBukkit.mock();
        context = mock(VoyageContext.class);
        command = new BorderScmd(context);
    }

    @AfterEach
    void tearDown() {
        MockBukkit.unmock();
    }

    @Test
    void arguments_areExactlyWorldAndSize() {
        assertEquals(2, command.getArguments().size());
        assertEquals("border", command.getName());
        assertEquals("voyage.border", command.getPermission());
    }

    @Test
    void nonFiniteBorderSizes_areRejectedWithoutChangingWorldBorder() {
        World world = server.addSimpleWorld("border-test");
        WorldBorder border = world.getWorldBorder();
        double original = border.getSize();

        Player player = server.addPlayer();

        @SuppressWarnings("unchecked")
        CommandContext<CommandSourceStack> ctx = mock(CommandContext.class);

        when(ctx.getArgument("world", NamespacedKey.class)).thenReturn(world.getKey());
        when(ctx.getArgument("size", String.class)).thenReturn("NaN");

        assertDoesNotThrow(() -> command.execute(ctx, player));
        assertEquals(original, border.getSize(), 0.0001);

        when(ctx.getArgument("size", String.class)).thenReturn("Infinity");
        assertDoesNotThrow(() -> command.execute(ctx, player));
        assertEquals(original, border.getSize(), 0.0001);
    }

    @Test
    void reset_setsPaperMaximumBorderSize() {
        World world = server.addSimpleWorld("border-reset");
        Player player = server.addPlayer();

        @SuppressWarnings("unchecked")
        CommandContext<CommandSourceStack> ctx = mock(CommandContext.class);

        when(ctx.getArgument("world", NamespacedKey.class)).thenReturn(world.getKey());
        when(ctx.getArgument("size", String.class)).thenReturn("reset");

        command.execute(ctx, player);

        assertEquals(5.9999968E7, world.getWorldBorder().getSize(), 0.1);
    }
}
