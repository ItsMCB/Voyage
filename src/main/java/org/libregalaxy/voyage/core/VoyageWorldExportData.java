package org.libregalaxy.voyage.core;

import com.google.gson.Gson;
import org.bukkit.Bukkit;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.io.Reader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.*;
import java.util.logging.Level;

/**
 * Standardized metadata specification serialized into {@code voyage.json} inside
 * exported world archives. Encapsulates full dimension configuration and Paper-native
 * player profile states.
 */
public record VoyageWorldExportData(
        int version,
        @NotNull String worldKey,
        @Nullable String environment,
        long seed,
        @Nullable String worldType,
        @Nullable String generator,
        @Nullable String generatorSettings,
        boolean autoLoad,
        @Nullable String difficulty,
        boolean generateStructures,
        @Nullable int[] spawn,
        @Nullable float[] spawnAngle,
        @NotNull Map<String, String> gameRules,
        @NotNull Map<String, String> metadata,
        @NotNull List<PortalLinkData> portalLinks,
        @NotNull Map<String, PlayerStateSerializer.PlayerState> playerStates
) {
    public static final int CURRENT_VERSION = 1;

    public record PortalLinkData(
            @NotNull String portalType,
            @NotNull String targetWorld,
            @NotNull String strategyType,
            @Nullable Double strategyFactor,
            @NotNull String generatorType,
            @Nullable String structureKey
    ) {}

    public static @NotNull VoyageWorldExportData create(
            @NotNull String worldKey,
            @Nullable String environment,
            long seed,
            @Nullable String worldType,
            @Nullable String generator,
            @Nullable String generatorSettings,
            boolean autoLoad,
            @Nullable String difficulty,
            boolean generateStructures,
            @Nullable int[] spawn,
            @Nullable float[] spawnAngle,
            @Nullable Map<String, String> gameRules,
            @Nullable Map<String, String> metadata,
            @Nullable List<PortalLinkData> portalLinks,
            @Nullable Map<UUID, PlayerStateSerializer.PlayerState> playerStates
    ) {
        Map<String, PlayerStateSerializer.PlayerState> stringKeyMap = new LinkedHashMap<>();
        if (playerStates != null) {
            for (Map.Entry<UUID, PlayerStateSerializer.PlayerState> entry : playerStates.entrySet()) {
                if (entry.getKey() != null && entry.getValue() != null) {
                    stringKeyMap.put(entry.getKey().toString(), entry.getValue());
                }
            }
        }

        return new VoyageWorldExportData(
                CURRENT_VERSION,
                worldKey,
                environment,
                seed,
                worldType,
                generator,
                generatorSettings,
                autoLoad,
                difficulty,
                generateStructures,
                spawn != null ? spawn.clone() : null,
                spawnAngle != null ? spawnAngle.clone() : null,
                gameRules != null ? Map.copyOf(gameRules) : Map.of(),
                metadata != null ? Map.copyOf(metadata) : Map.of(),
                portalLinks != null ? List.copyOf(portalLinks) : List.of(),
                stringKeyMap
        );
    }

    public @NotNull Map<UUID, PlayerStateSerializer.PlayerState> getPlayerStatesAsUuidMap() {
        Map<UUID, PlayerStateSerializer.PlayerState> map = new LinkedHashMap<>();
        if (playerStates != null) {
            for (Map.Entry<String, PlayerStateSerializer.PlayerState> entry : playerStates.entrySet()) {
                try {
                    map.put(UUID.fromString(entry.getKey()), entry.getValue());
                } catch (IllegalArgumentException ignored) {}
            }
        }
        return map;
    }

    public @NotNull String toJson(@NotNull Gson gson) {
        return gson.toJson(this);
    }

    public static @Nullable VoyageWorldExportData fromJson(@NotNull String json, @NotNull Gson gson) {
        try {
            return gson.fromJson(json, VoyageWorldExportData.class);
        } catch (Exception e) {
            Bukkit.getLogger().log(Level.WARNING, "Failed to parse voyage.json: " + e.getMessage(), e);
            return null;
        }
    }

    public static @Nullable VoyageWorldExportData fromPath(@NotNull Path path, @NotNull Gson gson) {
        if (!Files.isRegularFile(path)) return null;
        try (Reader reader = Files.newBufferedReader(path, StandardCharsets.UTF_8)) {
            return gson.fromJson(reader, VoyageWorldExportData.class);
        } catch (Exception e) {
            Bukkit.getLogger().log(Level.WARNING, "Failed to parse voyage.json from " + path + ": " + e.getMessage(), e);
            return null;
        }
    }
}