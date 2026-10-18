package org.libregalaxy.voyage.core;

import dev.dejvokep.boostedyaml.YamlDocument;
import dev.dejvokep.boostedyaml.block.implementation.Section;
import dev.dejvokep.boostedyaml.route.Route;
import dev.dejvokep.boostedyaml.settings.dumper.DumperSettings;
import dev.dejvokep.boostedyaml.settings.general.GeneralSettings;
import dev.dejvokep.boostedyaml.settings.loader.LoaderSettings;
import dev.dejvokep.boostedyaml.settings.updater.UpdaterSettings;
import org.bukkit.Bukkit;
import org.bukkit.Difficulty;
import org.bukkit.GameRule;
import org.bukkit.NamespacedKey;
import org.bukkit.Registry;
import org.bukkit.World;
import org.bukkit.WorldType;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.plugin.java.JavaPlugin;
import org.bukkit.scheduler.BukkitTask;
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

import java.io.File;
import java.io.IOException;
import java.util.*;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.concurrent.ConcurrentHashMap;
import java.util.logging.Level;

public class WorldConfigService {

    private final JavaPlugin plugin;
    private final VoyageWorldManager manager;

    private final Object configLock = new Object();
    private volatile YamlDocument config;

    private volatile boolean loading = false;

    private final Object saveLock = new Object();
    private BukkitTask pendingSaveTask;
    private boolean dirty = false;

    private final Thread shutdownHook;

    public WorldConfigService(@NotNull JavaPlugin plugin, @NotNull VoyageWorldManager manager) {
        this.plugin = plugin;
        this.manager = manager;

        try {
            this.config = YamlDocument.create(
                    new File(plugin.getDataFolder(), "worlds.yml"),
                    GeneralSettings.builder().setUseDefaults(false).build(),
                    LoaderSettings.builder().setAutoUpdate(false).build(),
                    DumperSettings.DEFAULT,
                    UpdaterSettings.DEFAULT);
        } catch (IOException e) {
            plugin.getLogger().log(Level.SEVERE, "Failed to initialise worlds.yml — world persistence is disabled!", e);
        }

        this.shutdownHook = new Thread(() -> {
            boolean need;
            synchronized (saveLock) {
                need = dirty;
                dirty = false;
            }
            if (need) performSaveSync();
        }, "voyage-shutdown-save");
        Runtime.getRuntime().addShutdownHook(shutdownHook);
    }

    public void reload() {
        synchronized (configLock) {
            try {
                if (config != null) config.reload();
            } catch (IOException ignored) {}
        }
        loadState(true);
    }

    public void shutdown() {
        try {
            Runtime.getRuntime().removeShutdownHook(shutdownHook);
        } catch (IllegalStateException ignored) {}

        boolean need;
        synchronized (saveLock) {
            if (pendingSaveTask != null) {
                pendingSaveTask.cancel();
                pendingSaveTask = null;
            }
            need = dirty;
            dirty = false;
        }
        if (need) performSaveSync();
    }

    public void saveState() {
        if (loading || config == null) return;
        synchronized (saveLock) {
            dirty = true;
            if (pendingSaveTask != null) return;
            pendingSaveTask = Bukkit.getScheduler().runTaskLaterAsynchronously(plugin, () -> {
                boolean shouldSave;
                synchronized (saveLock) {
                    pendingSaveTask = null;
                    shouldSave = dirty;
                    dirty = false;
                }
                if (shouldSave) performSaveSync();
            }, 100L);
        }
    }

    public void performSaveSync() {
        synchronized (configLock) {
            config.remove("worlds");
            for (ManagedWorldImpl mw : manager.getRegistry().values()) {
                if (mw.isTemporary()) continue;
                Route base = Route.from("worlds", mw.getKey().asString());

                boolean isVoid = VoyageAPI.isVoidWorld(mw.getGenerator().orElse(null), mw.getGeneratorSettings().orElse(null));

                config.set(base.add("environment"),       mw.getEnvironment().name());
                config.set(base.add("seed"),              mw.getSeed());
                config.set(base.add("generator"),         isVoid ? (plugin.getName() + ":void") : mw.getGenerator().orElse(null));
                config.set(base.add("worldType"),         isVoid ? WorldType.FLAT.name() : mw.getWorldType().name());
                config.set(base.add("generatorSettings"), isVoid ? VoyageAPI.VANILLA_VOID_SETTINGS : mw.getGeneratorSettings().orElse(null));
                config.set(base.add("autoLoad"),          mw.isAutoLoad());

                config.set(base.add("difficulty"),         mw.getDifficulty().name());
                config.set(base.add("generateStructures"), !isVoid && mw.isGenerateStructures());

                int[] spawnOverride = mw.getSpawnOverride();
                if (spawnOverride != null) {
                    config.set(base.add("spawn").add("x"), spawnOverride[0]);
                    config.set(base.add("spawn").add("y"), spawnOverride[1]);
                    config.set(base.add("spawn").add("z"), spawnOverride[2]);
                } else {
                    config.remove(base.add("spawn"));
                }

                Map<GameRule<?>, Object> rules = mw.getGameRules();
                if (rules.isEmpty()) {
                    config.remove(base.add("gamerules"));
                } else {
                    Route grBase = base.add("gamerules");
                    for (Map.Entry<GameRule<?>, Object> entry : rules.entrySet()) {
                        String ruleName = entry.getKey().getKey().value();
                        config.set(grBase.add(ruleName), String.valueOf(entry.getValue()));
                    }
                }

                Map<String, String> meta = mw.getAllMetadata();
                if (meta.isEmpty()) {
                    config.remove(base.add("metadata"));
                } else {
                    Route metaBase = base.add("metadata");
                    for (Map.Entry<String, String> entry : meta.entrySet()) {
                        config.set(metaBase.add(entry.getKey()), entry.getValue());
                    }
                }

                for (PortalType type : PortalType.values()) {
                    mw.getPortalLink(type).ifPresent(link -> {
                        Route lp = base.add("links").add(type.name());
                        config.set(lp.add("target"), link.targetWorld().asString());

                        if (link.coordinateStrategy() instanceof VanillaScalingStrategy vs) {
                            config.set(lp.add("strategy").add("type"),   "VANILLA_SCALING");
                            config.set(lp.add("strategy").add("factor"), vs.getScaleFactor());
                        } else if (link.coordinateStrategy() instanceof FixedSpawnStrategy) {
                            config.set(lp.add("strategy").add("type"), "TARGET_SPAWN_POINT");
                        }

                        if (link.generator() instanceof VanillaNetherGenerator) {
                            config.set(lp.add("generator").add("type"), "VANILLA_NETHER");
                        } else if (link.generator() instanceof StructureGenerator sg) {
                            config.set(lp.add("generator").add("type"),         "STRUCTURE");
                            config.set(lp.add("generator").add("structureKey"), sg.getStructureKey().asString());
                        } else if (link.generator() instanceof NoneGenerator) {
                            config.set(lp.add("generator").add("type"), "NONE");
                        }
                    });
                }
            }
            try {
                config.save();
            } catch (IOException e) {
                plugin.getLogger().severe("Failed to save worlds.yml!");
            }
        }
    }

    public void loadState(boolean isReload) {
        if (config == null) return;

        synchronized (this) {
            if (loading) {
                plugin.getLogger().warning("loadState invoked while a previous load is still in progress — skipping.");
                return;
            }
            loading = true;
        }

        List<CompletableFuture<ManagedWorld>> pending = new ArrayList<>();
        Set<NamespacedKey> successfullyProcessed = ConcurrentHashMap.newKeySet();

        try {
            synchronized (configLock) {
                Section worldsSection = config.getSection("worlds");
                if (worldsSection != null) {
                    for (Object keyObj : worldsSection.getKeys()) {
                        String keyStr = keyObj.toString();
                        NamespacedKey key = NamespacedKey.fromString(keyStr);
                        Section wSec = worldsSection.getSection(Route.from(keyStr));

                        if (key == null) {
                            plugin.getLogger().warning("Invalid world key '" + keyStr + "' in worlds.yml — skipping.");
                            continue;
                        }
                        if (wSec.getBoolean("temporary", false)) {
                            plugin.getLogger().warning("Temporary world '" + keyStr + "' found in worlds.yml — skipping.");
                            continue;
                        }
                        successfullyProcessed.add(key);
                        boolean preExisting = isReload && manager.getRegistry().containsKey(key);
                        ManagedWorldImpl existing = preExisting ? manager.getRegistry().get(key) : null;

                        String generator = wSec.getString("generator", null);
                        String generatorSettings = wSec.getString("generatorSettings", null);
                        boolean isVoid = VoyageAPI.isVoidWorld(generator, generatorSettings);

                        WorldBuilderImpl builder = preExisting ? null : manager.builderInternal(key);
                        if (builder != null) {
                            WorldType worldType = isVoid ? WorldType.FLAT : WorldType.NORMAL;
                            if (!isVoid) {
                                String worldTypeName = wSec.getString("worldType", "NORMAL");
                                try {
                                    worldType = WorldType.valueOf(worldTypeName);
                                } catch (IllegalArgumentException e) {
                                    plugin.getLogger().warning("Unknown WorldType '" + worldTypeName + "' for world " + key + ". Defaulting to NORMAL.");
                                }
                            }

                            World.Environment env = World.Environment.NORMAL;
                            String envName = wSec.getString("environment", "NORMAL");
                            try {
                                env = World.Environment.valueOf(envName);
                            } catch (IllegalArgumentException e) {
                                plugin.getLogger().warning("Unknown Environment '" + envName + "' for world " + key + ". Defaulting to NORMAL.");
                            }

                            builder.environment(env)
                                    .seed(wSec.getLong("seed", 0L))
                                    .generator(isVoid ? (plugin.getName() + ":void") : generator)
                                    .type(worldType)
                                    .generatorSettings(isVoid ? VoyageAPI.VANILLA_VOID_SETTINGS : generatorSettings)
                                    .autoLoad(wSec.getBoolean("autoLoad", true));
                        }

                        Difficulty difficulty = Difficulty.NORMAL;
                        String diffStr = wSec.getString("difficulty", "NORMAL");
                        try {
                            difficulty = Difficulty.valueOf(diffStr);
                        } catch (IllegalArgumentException e) {
                            plugin.getLogger().warning("Unknown difficulty '" + diffStr + "' for world " + key + ". Defaulting to NORMAL.");
                        }

                        boolean generateStructures = !isVoid && wSec.getBoolean("generateStructures", true);

                        int[] spawn = null;
                        Section spawnSec = wSec.getSection("spawn");
                        if (spawnSec != null) {
                            spawn = new int[]{
                                    spawnSec.getInt("x"),
                                    spawnSec.getInt("y"),
                                    spawnSec.getInt("z")
                            };
                        }

                        Map<GameRule<?>, Object> gameRules = new LinkedHashMap<>();
                        Section grSec = wSec.getSection("gamerules");
                        if (grSec != null) {
                            for (Object grKeyObj : grSec.getKeys()) {
                                String ruleName = grKeyObj.toString();
                                String snakeCaseRule = ruleName.replaceAll("([a-z])([A-Z]+)", "$1_$2").toLowerCase(Locale.ROOT);
                                NamespacedKey ruleKey = NamespacedKey.fromString(snakeCaseRule);
                                if (ruleKey == null) continue;
                                GameRule<?> rule = Registry.GAME_RULE.get(ruleKey);
                                if (rule == null) continue;
                                String valStr = grSec.getString(ruleName);
                                Object val = parseGameRuleValue(rule, valStr);
                                if (val != null) gameRules.put(rule, val);
                            }
                        }

                        Map<String, String> metadata = new LinkedHashMap<>();
                        Section metaSec = wSec.getSection("metadata");
                        if (metaSec != null) {
                            for (Object mKeyObj : metaSec.getKeys()) {
                                String mKey = mKeyObj.toString();
                                String mVal = metaSec.getString(mKey);
                                if (mVal != null) metadata.put(mKey, mVal);
                            }
                        }

                        if (existing != null) {
                            existing.setAutoLoad(wSec.getBoolean("autoLoad", true));
                            existing.clearLinks();
                            existing.setDifficultyInternal(difficulty);
                            existing.setSpawnInternal(spawn);
                            existing.clearGameRulesInternal();
                            existing.setGameRulesInternal(gameRules);
                            existing.setMetadataInternal(metadata);
                            if (isVoid) {
                                existing.setWorldType(WorldType.FLAT);
                                existing.setGeneratorSettings(VoyageAPI.VANILLA_VOID_SETTINGS);
                                existing.setGenerator(plugin.getName() + ":void");
                            }
                        }

                        if (builder != null) {
                            builder.difficulty(difficulty)
                                    .generateStructures(generateStructures);
                            if (spawn != null) builder.spawn(spawn[0], spawn[1], spawn[2]);
                            for (Map.Entry<GameRule<?>, Object> entry : gameRules.entrySet()) {
                                applyGameRuleToBuilder(builder, entry.getKey(), entry.getValue());
                            }
                            builder.metadata(metadata);
                        }

                        Section linksSec = wSec.getSection("links");
                        if (linksSec != null) {
                            for (Object ltObj : linksSec.getKeys()) {
                                String typeStr = ltObj.toString();
                                PortalType type;
                                try {
                                    type = PortalType.valueOf(typeStr);
                                } catch (IllegalArgumentException e) {
                                    continue;
                                }

                                Section lSec = linksSec.getSection(typeStr);
                                NamespacedKey target = NamespacedKey.fromString(lSec.getString("target", ""));
                                if (target == null) continue;

                                CoordinateStrategy strategy;
                                if ("VANILLA_SCALING".equals(lSec.getString("strategy.type"))) {
                                    double f = lSec.getDouble("strategy.factor", 1.0);
                                    strategy = new VanillaScalingStrategy(f <= 0.0 ? 1.0 : f);
                                } else {
                                    strategy = new FixedSpawnStrategy();
                                }

                                PortalGenerator generatorInstance;
                                String genType = lSec.getString("generator.type", "NONE");
                                if ("STRUCTURE".equals(genType)) {
                                    NamespacedKey sk = NamespacedKey.fromString(lSec.getString("generator.structureKey", ""));
                                    generatorInstance = sk != null ? new StructureGenerator(sk) : new NoneGenerator();
                                } else if ("VANILLA_NETHER".equals(genType)) {
                                    generatorInstance = new VanillaNetherGenerator();
                                } else {
                                    generatorInstance = new NoneGenerator();
                                }

                                PortalLink link = new PortalLink(target, strategy, generatorInstance);
                                if (existing != null) existing.linkPortal(type, link);
                                else if (builder != null) builder.linkPortal(type, link);
                            }
                        }

                        if (!preExisting && builder != null) {
                            pending.add(builder.create().handle((mw, ex) -> {
                                if (ex != null) {
                                    Throwable cause = ex instanceof CompletionException ? ex.getCause() : ex;
                                    plugin.getLogger().log(Level.WARNING, "Failed to load '" + key + "': " + cause.getMessage());
                                    successfullyProcessed.remove(key);
                                    return null;
                                }
                                return mw;
                            }));
                        }
                    }
                }

                try {
                    ConfigurationSection groups = plugin.getConfig().getConfigurationSection("world-groups");
                    if (groups != null) {
                        for (String group : groups.getKeys(false)) {
                            for (String wKeyStr : groups.getStringList(group)) {
                                NamespacedKey key = NamespacedKey.fromString(wKeyStr);
                                if (key == null || successfullyProcessed.contains(key) || (isReload && manager.getRegistry().containsKey(key))) continue;
                                successfullyProcessed.add(key);

                                World.Environment env = World.Environment.NORMAL;
                                String kv = key.getKey().toLowerCase(Locale.ROOT);
                                if (kv.equals("the_nether") || kv.endsWith("_nether"))        env = World.Environment.NETHER;
                                else if (kv.equals("the_end") || kv.endsWith("_end"))         env = World.Environment.THE_END;

                                plugin.getLogger().info("Auto-importing data-linked dimension: " + wKeyStr);

                                WorldBuilderImpl b = manager.builderInternal(key);
                                b.importExisting(true).autoLoad(true).environment(env);

                                pending.add(b.create().handle((mw, ex) -> {
                                    if (ex != null) {
                                        Throwable cause = ex instanceof CompletionException ? ex.getCause() : ex;
                                        plugin.getLogger().log(Level.SEVERE, "Failed to auto-import '" + key + "': " + cause.getMessage());
                                        successfullyProcessed.remove(key);
                                        return null;
                                    }
                                    return mw;
                                }));
                            }
                        }
                    }
                } catch (Exception e) {
                    plugin.getLogger().severe("Exception auto-importing world-groups: " + e.getMessage());
                }
            }
        } catch (Exception e) {
            plugin.getLogger().log(Level.SEVERE, "Exception parsing worlds.yml: " + e.getMessage(), e);
            loading = false;
            return;
        }

        Runnable onComplete = () -> {
            loading = false;
            if (isReload) {
                Set<NamespacedKey> toRemove = new HashSet<>(manager.getRegistry().keySet());
                toRemove.removeAll(successfullyProcessed);
                for (NamespacedKey keyToRemove : toRemove) {
                    manager.getRegistry().remove(keyToRemove);
                    World w = Bukkit.getWorld(keyToRemove);
                    if (w != null) {
                        Bukkit.getScheduler().runTask(plugin, () -> {
                            if (!Bukkit.unloadWorld(w, true)) {
                                plugin.getLogger().warning("Failed to unload untracked world " + keyToRemove + " during reload.");
                            }
                        });
                    }
                }
            }
            saveState();
        };

        if (pending.isEmpty()) {
            onComplete.run();
        } else {
            CompletableFuture.allOf(pending.toArray(new CompletableFuture[0]))
                    .whenComplete((v, ex) -> {
                        if (ex != null) plugin.getLogger().log(Level.SEVERE, "Unexpected error during world load batch", ex);
                        onComplete.run();
                    });
        }
    }

    @SuppressWarnings("unchecked")
    public static Object parseGameRuleValue(GameRule<?> rule, String valStr) {
        if (valStr == null) return null;
        if (rule.getType() == Boolean.class) {
            return Boolean.parseBoolean(valStr);
        } else if (rule.getType() == Integer.class) {
            try {
                return Integer.parseInt(valStr);
            } catch (NumberFormatException e) {
                return null;
            }
        }
        return null;
    }

    @SuppressWarnings("unchecked")
    public static <T> void applyGameRuleToBuilder(WorldBuilder builder, GameRule<T> rule, Object value) {
        builder.gameRule(rule, (T) value);
    }
}