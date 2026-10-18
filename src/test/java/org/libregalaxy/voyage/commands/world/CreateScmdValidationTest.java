package org.libregalaxy.voyage.commands.world;

import org.bukkit.GameRule;
import org.bukkit.NamespacedKey;
import org.junit.jupiter.api.Test;
import org.libregalaxy.voyage.api.builder.WorldBuilder;

import java.lang.reflect.Method;

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
}