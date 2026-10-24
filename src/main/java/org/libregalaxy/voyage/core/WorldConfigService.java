package org.libregalaxy.voyage.core;

import org.bukkit.Bukkit;
import org.bukkit.Difficulty;
import org.bukkit.GameRule;
import org.bukkit.NamespacedKey;
import org.bukkit.Registry;
import org.bukkit.World;
import org.bukkit.WorldType;
import org.bukkit.plugin.java.JavaPlugin;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;
import org.libregalaxy.voyage.api.ManagedWorld;
import org.libregalaxy.voyage.api.VoyageAPI;
import org.libregalaxy.voyage.api.builder.WorldBuilder;
import org.libregalaxy.voyage.api.portal.CoordinateStrategy;
import org.libregalaxy.voyage.api.portal.PortalGenerator;
import org.libregalaxy.voyage.api.portal.PortalLink;
import org.libregalaxy.voyage.api.portal.PortalType;
import org.libregalaxy.voyage.api.portal.generator.NoneGenerator;
import org.libregalaxy.voyage.api.portal.generator.StructureGenerator;
import org.libregalaxy.voyage.api.portal.generator.VanillaNetherGenerator;
import org.libregalaxy.voyage.api.portal.strategy.FixedSpawnStrategy;
import org.libregalaxy.voyage.api.portal.strategy.VanillaScalingStrategy;
import org.libregalaxy.voyage.core.database.VoyageDatabaseService;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.*;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.logging.Level;

/**
 * Manages world definition persistence using high-performance SQLite operations.
 * Completely replaces the monolithic worlds.yml file.
 */
public class WorldConfigService {

    private final JavaPlugin plugin;
    private final VoyageWorldManager manager;
    private final VoyageDatabaseService databaseService;

    public WorldConfigService(@NotNull JavaPlugin plugin,
                              @NotNull VoyageWorldManager manager,
                              @Nullable VoyageDatabaseService databaseService) {
        this.plugin = plugin;
        this.manager = manager;
        this.databaseService = databaseService != null
                ? databaseService
                : VoyageDatabaseService.resolve(manager.getContext());

        if (this.databaseService != null) {
            loadState(false);
        }
    }

    public void reload() {
        if (databaseService != null) {
            loadState(true);
        }
    }

    public void shutdown() {}

    public void saveWorldAsync(@NotNull ManagedWorldImpl mw) {
        if (mw.isTemporary() || databaseService == null) return;

        Bukkit.getScheduler().runTaskAsynchronously(plugin, () -> {
            try {
                saveWorldSync(mw);
            } catch (SQLException e) {
                plugin.getLogger().log(Level.SEVERE, "Failed to save world to database: " + mw.getKey(), e);
            }
        });
    }

    public synchronized void saveWorldSync(@NotNull ManagedWorldImpl mw) throws SQLException {
        if (mw.isTemporary() || databaseService == null) return;

        NamespacedKey key = mw.getKey();
        boolean isVoid = VoyageAPI.isVoidWorld(mw.getGenerator().orElse(null), mw.getGeneratorSettings().orElse(null));

        try (Connection conn = databaseService.getConnection()) {
            conn.setAutoCommit(false);
            try {
                String upsertWorld = """
                    INSERT INTO voyage_worlds (
                        namespace, key, environment, seed, world_type, generator, generator_settings,
                        auto_load, difficulty, generate_structures, spawn_x, spawn_y, spawn_z, spawn_yaw, spawn_pitch, created_at
                    ) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
                    ON CONFLICT(namespace, key) DO UPDATE SET
                        environment = excluded.environment,
                        seed = excluded.seed,
                        world_type = excluded.world_type,
                        generator = excluded.generator,
                        generator_settings = excluded.generator_settings,
                        auto_load = excluded.auto_load,
                        difficulty = excluded.difficulty,
                        generate_structures = excluded.generate_structures,
                        spawn_x = excluded.spawn_x,
                        spawn_y = excluded.spawn_y,
                        spawn_z = excluded.spawn_z,
                        spawn_yaw = excluded.spawn_yaw,
                        spawn_pitch = excluded.spawn_pitch;
                """;

                try (PreparedStatement ps = conn.prepareStatement(upsertWorld)) {
                    ps.setString(1, key.getNamespace());
                    ps.setString(2, key.getKey());
                    ps.setString(3, mw.getEnvironment().name());
                    ps.setLong(4, mw.getSeed());
                    ps.setString(5, isVoid ? WorldType.FLAT.name() : mw.getWorldType().name());
                    ps.setString(6, isVoid ? (plugin.getName() + ":void") : mw.getGenerator().orElse(null));
                    ps.setString(7, isVoid ? VoyageAPI.VANILLA_VOID_SETTINGS : mw.getGeneratorSettings().orElse(null));
                    ps.setInt(8, mw.isAutoLoad() ? 1 : 0);
                    ps.setString(9, mw.getDifficulty().name());
                    ps.setInt(10, (!isVoid && mw.isGenerateStructures()) ? 1 : 0);

                    int[] spawn = mw.getSpawnOverride();
                    if (spawn != null) {
                        ps.setInt(11, spawn[0]);
                        ps.setInt(12, spawn[1]);
                        ps.setInt(13, spawn[2]);
                    } else {
                        ps.setNull(11, java.sql.Types.INTEGER);
                        ps.setNull(12, java.sql.Types.INTEGER);
                        ps.setNull(13, java.sql.Types.INTEGER);
                    }

                    float[] angle = mw.getSpawnAngleOverride();
                    if (angle != null) {
                        ps.setFloat(14, angle[0]);
                        ps.setFloat(15, angle[1]);
                    } else {
                        ps.setNull(14, java.sql.Types.REAL);
                        ps.setNull(15, java.sql.Types.REAL);
                    }

                    ps.setLong(16, System.currentTimeMillis());
                    ps.executeUpdate();
                }

                try (PreparedStatement delGr = conn.prepareStatement(
                        "DELETE FROM voyage_world_gamerules WHERE world_namespace = ? AND world_key = ?;")) {
                    delGr.setString(1, key.getNamespace());
                    delGr.setString(2, key.getKey());
                    delGr.executeUpdate();
                }

                Map<GameRule<?>, Object> rules = mw.getGameRules();
                if (!rules.isEmpty()) {
                    try (PreparedStatement insGr = conn.prepareStatement(
                            "INSERT INTO voyage_world_gamerules (world_namespace, world_key, rule_name, rule_value) VALUES (?, ?, ?, ?);")) {
                        for (Map.Entry<GameRule<?>, Object> entry : rules.entrySet()) {
                            insGr.setString(1, key.getNamespace());
                            insGr.setString(2, key.getKey());
                            insGr.setString(3, entry.getKey().getKey().value());
                            insGr.setString(4, String.valueOf(entry.getValue()));
                            insGr.addBatch();
                        }
                        insGr.executeBatch();
                    }
                }

                try (PreparedStatement delMeta = conn.prepareStatement(
                        "DELETE FROM voyage_world_metadata WHERE world_namespace = ? AND world_key = ?;")) {
                    delMeta.setString(1, key.getNamespace());
                    delMeta.setString(2, key.getKey());
                    delMeta.executeUpdate();
                }

                Map<String, String> meta = mw.getAllMetadata();
                if (!meta.isEmpty()) {
                    try (PreparedStatement insMeta = conn.prepareStatement(
                            "INSERT INTO voyage_world_metadata (world_namespace, world_key, meta_key, meta_value) VALUES (?, ?, ?, ?);")) {
                        for (Map.Entry<String, String> entry : meta.entrySet()) {
                            insMeta.setString(1, key.getNamespace());
                            insMeta.setString(2, key.getKey());
                            insMeta.setString(3, entry.getKey());
                            insMeta.setString(4, entry.getValue());
                            insMeta.addBatch();
                        }
                        insMeta.executeBatch();
                    }
                }

                try (PreparedStatement delLinks = conn.prepareStatement(
                        "DELETE FROM voyage_portal_links WHERE source_namespace = ? AND source_key = ?;")) {
                    delLinks.setString(1, key.getNamespace());
                    delLinks.setString(2, key.getKey());
                    delLinks.executeUpdate();
                }

                try (PreparedStatement insLink = conn.prepareStatement("""
                    INSERT INTO voyage_portal_links (
                        source_namespace, source_key, portal_type, target_namespace, target_key,
                        strategy_type, strategy_factor, generator_type, structure_key
                    ) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?);
                """)) {
                    for (PortalType type : PortalType.values()) {
                        var linkOpt = mw.getPortalLink(type);
                        if (linkOpt.isPresent()) {
                            PortalLink link = linkOpt.get();
                            insLink.setString(1, key.getNamespace());
                            insLink.setString(2, key.getKey());
                            insLink.setString(3, type.name());
                            insLink.setString(4, link.targetWorld().getNamespace());
                            insLink.setString(5, link.targetWorld().getKey());

                            if (link.coordinateStrategy() instanceof VanillaScalingStrategy vs) {
                                insLink.setString(6, "VANILLA_SCALING");
                                insLink.setDouble(7, vs.getScaleFactor());
                            } else {
                                insLink.setString(6, "TARGET_SPAWN_POINT");
                                insLink.setNull(7, java.sql.Types.DOUBLE);
                            }

                            if (link.generator() instanceof VanillaNetherGenerator) {
                                insLink.setString(8, "VANILLA_NETHER");
                                insLink.setNull(9, java.sql.Types.VARCHAR);
                            } else if (link.generator() instanceof StructureGenerator sg) {
                                insLink.setString(8, "STRUCTURE");
                                insLink.setString(9, sg.getStructureKey().asString());
                            } else {
                                insLink.setString(8, "NONE");
                                insLink.setNull(9, java.sql.Types.VARCHAR);
                            }
                            insLink.addBatch();
                        }
                    }
                    insLink.executeBatch();
                }

                conn.commit();
            } catch (SQLException ex) {
                conn.rollback();
                throw ex;
            } finally {
                conn.setAutoCommit(true);
            }
        }
    }

    public void deleteWorld(@NotNull NamespacedKey key) {
        if (databaseService == null) return;
        Bukkit.getScheduler().runTaskAsynchronously(plugin, () -> {
            try (Connection conn = databaseService.getConnection()) {
                conn.setAutoCommit(false);
                try {
                    String ns = key.getNamespace();
                    String k = key.getKey();

                    try (PreparedStatement ps = conn.prepareStatement(
                            "DELETE FROM voyage_world_gamerules WHERE world_namespace = ? AND world_key = ?;")) {
                        ps.setString(1, ns);
                        ps.setString(2, k);
                        ps.executeUpdate();
                    }

                    try (PreparedStatement ps = conn.prepareStatement(
                            "DELETE FROM voyage_world_metadata WHERE world_namespace = ? AND world_key = ?;")) {
                        ps.setString(1, ns);
                        ps.setString(2, k);
                        ps.executeUpdate();
                    }

                    try (PreparedStatement ps = conn.prepareStatement(
                            "DELETE FROM voyage_portal_links WHERE source_namespace = ? AND source_key = ?;")) {
                        ps.setString(1, ns);
                        ps.setString(2, k);
                        ps.executeUpdate();
                    }

                    try (PreparedStatement ps = conn.prepareStatement(
                            "DELETE FROM voyage_worlds WHERE namespace = ? AND key = ?;")) {
                        ps.setString(1, ns);
                        ps.setString(2, k);
                        ps.executeUpdate();
                    }

                    conn.commit();
                } catch (SQLException ex) {
                    conn.rollback();
                    throw ex;
                } finally {
                    conn.setAutoCommit(true);
                }
            } catch (SQLException e) {
                plugin.getLogger().log(Level.SEVERE, "Failed to remove world from database: " + key, e);
            }
        });
    }

    public void loadState(boolean isReload) {
        if (databaseService == null) return;
        List<CompletableFuture<ManagedWorld>> pending = new ArrayList<>();
        Set<NamespacedKey> loadedKeys = new HashSet<>();

        try (Connection conn = databaseService.getConnection()) {
            String selectWorlds = "SELECT * FROM voyage_worlds;";
            try (PreparedStatement ps = conn.prepareStatement(selectWorlds);
                 ResultSet rs = ps.executeQuery()) {

                while (rs.next()) {
                    String ns = rs.getString("namespace");
                    String k = rs.getString("key");
                    NamespacedKey key = NamespacedKey.fromString(ns + ":" + k);
                    if (key == null) continue;

                    loadedKeys.add(key);
                    boolean preExisting = isReload && manager.getRegistry().containsKey(key);
                    ManagedWorldImpl existing = preExisting ? manager.getRegistry().get(key) : null;

                    String envName = rs.getString("environment");
                    World.Environment env = World.Environment.NORMAL;
                    try { env = World.Environment.valueOf(envName); } catch (Exception ignored) {}

                    long seed = rs.getLong("seed");
                    String worldTypeName = rs.getString("world_type");
                    WorldType worldType = WorldType.NORMAL;
                    try { worldType = WorldType.valueOf(worldTypeName); } catch (Exception ignored) {}

                    String generator = rs.getString("generator");
                    String generatorSettings = rs.getString("generator_settings");
                    boolean autoLoad = rs.getInt("auto_load") == 1;

                    String diffStr = rs.getString("difficulty");
                    Difficulty diff = Difficulty.NORMAL;
                    try { diff = Difficulty.valueOf(diffStr); } catch (Exception ignored) {}

                    boolean structures = rs.getInt("generate_structures") == 1;

                    int[] spawn = null;
                    int sx = rs.getInt("spawn_x");
                    if (!rs.wasNull()) {
                        spawn = new int[]{sx, rs.getInt("spawn_y"), rs.getInt("spawn_z")};
                    }

                    float[] spawnAngle = null;
                    float yaw = rs.getFloat("spawn_yaw");
                    if (!rs.wasNull()) {
                        spawnAngle = new float[]{yaw, rs.getFloat("spawn_pitch")};
                    }

                    boolean isVoid = VoyageAPI.isVoidWorld(generator, generatorSettings);

                    Map<GameRule<?>, Object> gamerules = loadGamerules(conn, ns, k);
                    Map<String, String> metadata = loadMetadata(conn, ns, k);
                    Map<PortalType, PortalLink> portalLinks = loadPortalLinks(conn, ns, k);

                    if (existing != null) {
                        existing.setAutoLoad(autoLoad);
                        existing.setDifficultyInternal(diff);
                        existing.setSpawnInternal(spawn);
                        existing.setSpawnAngleInternal(spawnAngle);
                        existing.clearGameRulesInternal();
                        existing.setGameRulesInternal(gamerules);
                        existing.setMetadataInternal(metadata);
                        existing.clearLinks();
                        portalLinks.forEach(existing::linkPortal);
                    } else {
                        WorldBuilderImpl builder = manager.builderInternal(key);
                        builder.environment(env)
                                .seed(seed)
                                .type(isVoid ? WorldType.FLAT : worldType)
                                .generator(isVoid ? (plugin.getName() + ":void") : generator)
                                .generatorSettings(isVoid ? VoyageAPI.VANILLA_VOID_SETTINGS : generatorSettings)
                                .autoLoad(autoLoad)
                                .difficulty(diff)
                                .generateStructures(!isVoid && structures);

                        if (spawn != null) builder.spawn(spawn[0], spawn[1], spawn[2]);
                        gamerules.forEach((rule, val) -> applyGameRuleToBuilder(builder, rule, val));
                        builder.metadata(metadata);
                        portalLinks.forEach(builder::linkPortal);

                        final float[] finalSpawnAngle = spawnAngle;
                        pending.add(builder.create().handle((mw, ex) -> {
                            if (ex != null) {
                                Throwable cause = ex instanceof CompletionException ? ex.getCause() : ex;
                                plugin.getLogger().log(Level.WARNING, "Failed to mount '" + key + "': " + cause.getMessage());
                                return null;
                            }
                            if (mw instanceof ManagedWorldImpl impl && finalSpawnAngle != null) {
                                impl.setSpawnAngleInternal(finalSpawnAngle);
                            }
                            return mw;
                        }));
                    }
                }
            }
        } catch (SQLException e) {
            plugin.getLogger().log(Level.SEVERE, "Failed to load world definitions from database", e);
            return;
        }

        if (isReload) {
            Set<NamespacedKey> toRemove = new HashSet<>(manager.getRegistry().keySet());
            toRemove.removeAll(loadedKeys);
            for (NamespacedKey removeKey : toRemove) {
                manager.getRegistry().remove(removeKey);
                World w = Bukkit.getWorld(removeKey);
                if (w != null) {
                    Bukkit.unloadWorld(w, true);
                }
            }
        }

        if (!pending.isEmpty()) {
            CompletableFuture.allOf(pending.toArray(new CompletableFuture[0]));
        }
    }

    private Map<GameRule<?>, Object> loadGamerules(Connection conn, String ns, String k) throws SQLException {
        Map<GameRule<?>, Object> rules = new LinkedHashMap<>();
        try (PreparedStatement ps = conn.prepareStatement(
                "SELECT rule_name, rule_value FROM voyage_world_gamerules WHERE world_namespace = ? AND world_key = ?;")) {
            ps.setString(1, ns);
            ps.setString(2, k);
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    String ruleName = rs.getString("rule_name");
                    String valStr = rs.getString("rule_value");
                    String snakeCase = ruleName.replaceAll("([a-z])([A-Z]+)", "$1_$2").toLowerCase(Locale.ROOT);
                    NamespacedKey rKey = NamespacedKey.fromString(snakeCase);
                    if (rKey != null) {
                        GameRule<?> rule = Registry.GAME_RULE.get(rKey);
                        if (rule != null) {
                            Object parsed = parseGameRuleValue(rule, valStr);
                            if (parsed != null) rules.put(rule, parsed);
                        }
                    }
                }
            }
        }
        return rules;
    }

    private Map<String, String> loadMetadata(Connection conn, String ns, String k) throws SQLException {
        Map<String, String> meta = new LinkedHashMap<>();
        try (PreparedStatement ps = conn.prepareStatement(
                "SELECT meta_key, meta_value FROM voyage_world_metadata WHERE world_namespace = ? AND world_key = ?;")) {
            ps.setString(1, ns);
            ps.setString(2, k);
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    meta.put(rs.getString("meta_key"), rs.getString("meta_value"));
                }
            }
        }
        return meta;
    }

    private Map<PortalType, PortalLink> loadPortalLinks(Connection conn, String ns, String k) throws SQLException {
        Map<PortalType, PortalLink> links = new EnumMap<>(PortalType.class);
        try (PreparedStatement ps = conn.prepareStatement("""
            SELECT portal_type, target_namespace, target_key, strategy_type, strategy_factor, generator_type, structure_key
            FROM voyage_portal_links WHERE source_namespace = ? AND source_key = ?;
        """)) {
            ps.setString(1, ns);
            ps.setString(2, k);
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    String typeStr = rs.getString("portal_type");
                    PortalType type;
                    try { type = PortalType.valueOf(typeStr); } catch (Exception ignored) { continue; }

                    NamespacedKey target = NamespacedKey.fromString(rs.getString("target_namespace") + ":" + rs.getString("target_key"));
                    if (target == null) continue;

                    String stratType = rs.getString("strategy_type");
                    CoordinateStrategy strategy;
                    if ("VANILLA_SCALING".equals(stratType)) {
                        double f = rs.getDouble("strategy_factor");
                        strategy = new VanillaScalingStrategy(f <= 0.0 ? 1.0 : f);
                    } else {
                        strategy = new FixedSpawnStrategy();
                    }

                    String genType = rs.getString("generator_type");
                    PortalGenerator generatorInstance;
                    if ("STRUCTURE".equals(genType)) {
                        NamespacedKey sk = NamespacedKey.fromString(rs.getString("structure_key"));
                        generatorInstance = sk != null ? new StructureGenerator(sk) : new NoneGenerator();
                    } else if ("VANILLA_NETHER".equals(genType)) {
                        generatorInstance = new VanillaNetherGenerator();
                    } else {
                        generatorInstance = new NoneGenerator();
                    }

                    links.put(type, new PortalLink(target, strategy, generatorInstance));
                }
            }
        }
        return links;
    }

    @SuppressWarnings("unchecked")
    public static Object parseGameRuleValue(GameRule<?> rule, String valStr) {
        if (valStr == null) return null;
        if (rule.getType() == Boolean.class) {
            return Boolean.parseBoolean(valStr);
        } else if (rule.getType() == Integer.class) {
            try { return Integer.parseInt(valStr); } catch (NumberFormatException e) { return null; }
        }
        return null;
    }

    @SuppressWarnings("unchecked")
    public static <T> void applyGameRuleToBuilder(WorldBuilder builder, GameRule<T> rule, Object value) {
        builder.gameRule(rule, (T) value);
    }
}