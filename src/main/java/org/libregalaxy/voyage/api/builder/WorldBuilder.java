package org.libregalaxy.voyage.api.builder;

import org.bukkit.Difficulty;
import org.bukkit.GameRule;
import org.bukkit.NamespacedKey;
import org.bukkit.World;
import org.bukkit.WorldType;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;
import org.libregalaxy.voyage.api.ManagedWorld;
import org.libregalaxy.voyage.api.portal.PortalLink;
import org.libregalaxy.voyage.api.portal.PortalType;

import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ThreadLocalRandom;

public interface WorldBuilder {
    WorldBuilder environment(World.Environment environment);
    WorldBuilder seed(long seed);

    /**
     * Sets the world seed from a string, converting it the same way vanilla
     * Minecraft does: if the string parses as a long it is used directly,
     * otherwise the string's {@link String#hashCode()} is used. A null or
     * blank string produces a random seed.
     */
    WorldBuilder seed(String seed);

    WorldBuilder generator(String generatorName);
    WorldBuilder type(WorldType worldType);
    WorldBuilder generatorSettings(String generatorSettings);
    WorldBuilder autoLoad(boolean autoLoad);

    /**
     * Sets the difficulty applied when the world is loaded.
     */
    WorldBuilder difficulty(Difficulty difficulty);

    /**
     * Controls whether structures generate in new chunks. Pass {@code false}
     * to disable all structure generation (blanket toggle).
     */
    WorldBuilder generateStructures(boolean generate);

    /**
     * Sets the world's fixed spawn location.
     */
    WorldBuilder spawn(int x, int y, int z);

    /**
     * Sets a gamerule that will be applied when the world is loaded.
     */
    <T> WorldBuilder gameRule(GameRule<T> rule, T value);

    /**
     * Adds a persistent metadata tag to the world.
     */
    WorldBuilder metadata(@NotNull String key, @Nullable String value);

    /**
     * Adds multiple persistent metadata tags to the world.
     */
    WorldBuilder metadata(@NotNull Map<String, String> metadata);

    WorldBuilder importExisting(boolean importExisting);

    WorldBuilder asTemporaryInstance();
    WorldBuilder fromTemplate(NamespacedKey templateKey);

    /**
     * Applies a preset from config.yml (e.g., "void", "flat").
     */
    WorldBuilder preset(String presetName);

    WorldBuilder linkPortal(PortalType type, PortalLink link);

    CompletableFuture<ManagedWorld> create();

    /**
     * Converts a string seed to a long following vanilla Minecraft behavior.
     * <ul>
     *   <li>If the string is null or blank, a random long is returned.</li>
     *   <li>If the string parses as a long, that value is returned.</li>
     *   <li>Otherwise, the string's {@link String#hashCode()} is returned.</li>
     * </ul>
     */
    static long parseSeed(String seed) {
        if (seed == null || seed.isBlank()) {
            return ThreadLocalRandom.current().nextLong();
        }
        try {
            return Long.parseLong(seed);
        } catch (NumberFormatException e) {
            return seed.hashCode();
        }
    }
}