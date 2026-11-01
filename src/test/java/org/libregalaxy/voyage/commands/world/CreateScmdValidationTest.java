package org.libregalaxy.voyage.commands.world;

import com.mojang.brigadier.context.CommandContext;
import io.papermc.paper.command.brigadier.CommandSourceStack;
import me.itsmcb.vexelcore.bukkit.api.framework.FlagParser;
import org.bukkit.GameRule;
import org.bukkit.NamespacedKey;
import org.bukkit.configuration.file.FileConfiguration;
import org.bukkit.entity.Player;
import org.bukkit.plugin.java.JavaPlugin;
import org.junit.jupiter.api.Test;
import org.libregalaxy.voyage.VoyageContext;
import org.libregalaxy.voyage.api.ManagedWorld;
import org.libregalaxy.voyage.api.VoyageAPI;
import org.libregalaxy.voyage.api.builder.WorldBuilder;
import org.libregalaxy.voyage.core.VoyageWorldManager;

import java.lang.reflect.Method;
import java.util.concurrent.CompletableFuture;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class CreateScmdValidationTest {

    @SuppressWarnings("unchecked")
    private static <T> boolean apply(
            WorldBuilder builder,
            GameRule<T> rule,
            String value
    ) throws Exception {
        Method method = CreateScmd.class.getDeclaredMethod(
                "applyGameruleToBuilder",
                WorldBuilder.class,
                GameRule.class,
                String.class
        );
        method.setAccessible(true);
        return (boolean) method.invoke(null, builder, rule, value);
    }

    @Test
    @SuppressWarnings({"deprecation", "removal"})
    void booleanGamerules_acceptOnlyTrueOrFalse() throws Exception {
        WorldBuilder builder = mock(WorldBuilder.class);

        assertTrue(apply(builder, GameRule.DO_DAYLIGHT_CYCLE, "true"));
        assertTrue(apply(builder, GameRule.DO_DAYLIGHT_CYCLE, "FALSE"));
        assertFalse(apply(builder, GameRule.DO_DAYLIGHT_CYCLE, "yes"));
        assertFalse(apply(builder, GameRule.DO_DAYLIGHT_CYCLE, "1"));

        verify(builder).gameRule(GameRule.DO_DAYLIGHT_CYCLE, true);
        verify(builder).gameRule(GameRule.DO_DAYLIGHT_CYCLE, false);
    }

    @Test
    @SuppressWarnings({"deprecation", "removal"})
    void integerGamerules_requireValidInteger() throws Exception {
        WorldBuilder builder = mock(WorldBuilder.class);

        assertTrue(apply(builder, GameRule.MAX_ENTITY_CRAMMING, "24"));
        assertFalse(apply(builder, GameRule.MAX_ENTITY_CRAMMING, "not-an-int"));

        verify(builder).gameRule(GameRule.MAX_ENTITY_CRAMMING, 24);
    }

    @Test
    void unknownNamespacedKey_isNotAcceptedByCreatePath() {
        assertNull(NamespacedKey.fromString("not a valid key"));
    }

    @Test
    void generatorFlag_parsesLongFormAndAlias() {
        VoyageContext context = mock(VoyageContext.class);
        JavaPlugin plugin = mock(JavaPlugin.class);
        FileConfiguration config = mock(FileConfiguration.class);
        when(context.getPlugin()).thenReturn(plugin);
        when(plugin.getConfig()).thenReturn(config);

        CreateScmd cmd = new CreateScmd(context);

        FlagParser longParsed = cmd.creationFlags.parse("--generator OldGenerator:b173");
        assertTrue(longParsed.getValue("generator").isPresent());
        assertEquals("OldGenerator:b173", longParsed.getValue("generator").get());

        FlagParser aliasParsed = cmd.creationFlags.parse("-gen OldGenerator:b173");
        assertTrue(aliasParsed.getValue("generator").isPresent());
        assertEquals("OldGenerator:b173", aliasParsed.getValue("generator").get());
    }

    @Test
    void generatorFlag_appliedToWorldBuilderDuringExecution() {
        VoyageContext context = mock(VoyageContext.class);
        JavaPlugin plugin = mock(JavaPlugin.class);
        FileConfiguration config = mock(FileConfiguration.class);
        VoyageWorldManager manager = mock(VoyageWorldManager.class);
        WorldBuilder builder = mock(WorldBuilder.class, RETURNS_SELF);
        ManagedWorld managedWorld = mock(ManagedWorld.class);

        when(context.getPlugin()).thenReturn(plugin);
        when(plugin.getConfig()).thenReturn(config);
        when(context.getWorldManager()).thenReturn(manager);

        NamespacedKey targetKey = NamespacedKey.fromString("custom:retro");
        when(manager.worldExists(targetKey)).thenReturn(false);
        when(manager.builder(targetKey)).thenReturn(builder);
        when(builder.create()).thenReturn(CompletableFuture.completedFuture(managedWorld));
        when(managedWorld.getBukkitWorld()).thenReturn(java.util.Optional.empty());

        CreateScmd cmd = new CreateScmd(context);
        Player player = mock(Player.class);

        @SuppressWarnings("unchecked")
        CommandContext<CommandSourceStack> ctx = mock(CommandContext.class);
        when(ctx.getArgument("namespacedkey", NamespacedKey.class)).thenReturn(targetKey);
        when(ctx.getArgument("flags", String.class)).thenReturn("--generator OldGenerator:b173");

        cmd.execute(ctx, player);

        verify(builder).generator("OldGenerator:b173");
        verify(builder).create();
    }
}