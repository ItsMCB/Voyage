package org.libregalaxy.voyage.commands.world;

import org.bukkit.NamespacedKey;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Regression tests for world folder name resolution during {@code /world import}.
 *
 * <h2>Context &amp; Root Cause:</h2>
 * In legacy Minecraft, world directories frequently used arbitrary casing (e.g., {@code CCC},
 * {@code Hub_1}, {@code World (Copy)}). Modern Paper/Minecraft requires {@link NamespacedKey}
 * keys to match the strict regex {@code [a-z0-9/._-]}.
 *
 * <p>Passing a string with uppercase characters (like {@code "custom:CCC"}) to
 * {@link NamespacedKey#fromString(String)} returns {@code null} rather than throwing an
 * exception or automatically lowercasing. This previously caused an unhandled
 * {@link NullPointerException} when calling {@code task.targetKey().asString()}.</p>
 *
 * <h2>Fix:</h2>
 * {@link ImportScmd#resolveNamespacedKey(String, String)} sanitizes all folder names to lowercase,
 * strips invalid characters to underscores, collapses multiple underscores, and trims boundaries.
 */
class ImportScmdKeyResolutionTest {

    @Test
    void uppercaseDirectory_resolvesToValidLowercaseKey() {
        NamespacedKey key = ImportScmd.resolveNamespacedKey("custom", "CCC");
        assertNotNull(key, "Uppercase folder name must not produce a null NamespacedKey");
        assertEquals("custom:ccc", key.asString());
    }

    @ParameterizedTest
    @CsvSource({
            "custom, CCC, custom:ccc",
            "custom, My World, custom:my_world",
            "custom, Hub_123, custom:hub_123",
            "my_ns, World (Copy), my_ns:world_copy",
            "CUSTOM, WORLD, custom:world",
            "custom, DIM-1, custom:dim-1"
    })
    void folderNames_areSanitizedProperly(String ns, String folder, String expected) {
        NamespacedKey key = ImportScmd.resolveNamespacedKey(ns, folder);
        assertNotNull(key);
        assertEquals(expected, key.asString());
    }

    @Test
    void blankOrNull_returnsNullSafely() {
        assertNull(ImportScmd.resolveNamespacedKey("custom", null));
        assertNull(ImportScmd.resolveNamespacedKey("custom", "   "));
    }
}