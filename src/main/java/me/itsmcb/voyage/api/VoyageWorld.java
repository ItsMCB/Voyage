package me.itsmcb.voyage.api;

import io.papermc.paper.registry.RegistryAccess;
import io.papermc.paper.registry.RegistryKey;
import libs.dev.dejvokep.boostedyaml.route.Route;
import libs.dev.dejvokep.boostedyaml.spigot.SpigotSerializer;
import me.itsmcb.vexelcore.common.api.config.BoostedConfig;
import me.itsmcb.vexelcore.common.api.utils.FileUtils;
import me.itsmcb.voyage.worldgen.biomeproviders.BuffetProvider;
import net.kyori.adventure.key.Key;
import org.bukkit.*;
import org.bukkit.block.Biome;
import org.bukkit.configuration.serialization.ConfigurationSerializable;
import org.bukkit.configuration.serialization.SerializableAs;
import org.bukkit.generator.BiomeProvider;
import org.jetbrains.annotations.NotNull;

import java.io.File;
import java.util.LinkedHashMap;
import java.util.Map;

@SerializableAs("VoyageWorld")
public class VoyageWorld implements ConfigurationSerializable {

    private String name;
    private WorldCreator worldCreator;
    private String generator;
    private String buffetBiomeName;
    private World world = null;
    private boolean loadOnStart = false;
    private BoostedConfig config;

    public VoyageWorld(String name) {
        this.name = name;
        this.worldCreator = new WorldCreator(name);
    }

    public VoyageWorld(World world) {
        this(world.getName());
        this.world = world;
    }

    public VoyageWorld setName(String name) {
        this.name = name;
        // Must recreate WorldCreator to set a new name
        this.worldCreator = new WorldCreator(name).copy(worldCreator);
        return this;
    }

    // TODO return world creator instead of having proxy methods

    public VoyageWorld setSeed(Long seed) {
        worldCreator.seed(seed);
        return this;
    }

    public VoyageWorld setWorldType(WorldType worldType) {
        worldCreator.type(worldType);
        return this;
    }

    public VoyageWorld setEnvironment(World.Environment environment) {
        worldCreator.environment(environment);
        return this;
    }

    public VoyageWorld setGenerateStructrues(boolean choice) {
        worldCreator.generateStructures(choice);
        return this;
    }

    public VoyageWorld setGeneratorSettings(String settings) {
        worldCreator.generatorSettings(settings);
        return this;
    }

    public VoyageWorld setDifficulty(Difficulty difficulty) {
        getWorld().setDifficulty(difficulty);
        return this;
    }

    public VoyageWorld setSingleWorldBiome(@NotNull Biome biome) {
        this.buffetBiomeName = biome.getKey().value();
        worldCreator.biomeProvider(getSingleBiomeProvider(this.buffetBiomeName));
        return this;
    }

    private BiomeProvider getSingleBiomeProvider(String biomeName) {
        return new BuffetProvider(RegistryAccess.registryAccess().getRegistry(RegistryKey.BIOME).get(Key.key(biomeName)));
    }

    public VoyageWorld setGenerator(String input) {
        this.generator = input;
        System.out.println("Gen input: "+generator);
        if (input == null) {
            return this;
        }
        // World types may be passed as a generator so Voyage will accommodate for that.
        if (input.equalsIgnoreCase("flat")) {
            setWorldType(WorldType.FLAT);
            return this;
        }
        if (input.equalsIgnoreCase("amplified")) {
            setWorldType(WorldType.AMPLIFIED);
            return this;
        }
        if (input.equalsIgnoreCase("largebiomes") || input.equalsIgnoreCase("large biomes")) {
            setWorldType(WorldType.LARGE_BIOMES);
            return this;
        }
        if (input.equalsIgnoreCase("normal")) {
            setWorldType(WorldType.NORMAL);
            return this;
        }
        worldCreator.generator(generator);
        return this;
    }

    public String getGenerator() {
        return generator;
    }

    public void setLoadOnStart(boolean loadOnStart) {
        this.loadOnStart = loadOnStart;
    }

    public boolean load() {
        if (world == null) {
            deserialize(getConfig().get().getStringRouteMappedValues(true));
            serializeAndSaveConfig();
        }
        // If world isn't loaded
        if (world == null || Bukkit.getWorld(getName()) == null) {
            world = worldCreator.createWorld();
        }
        return (world != null);
    }

    public boolean unload() {
        if (!isLoaded()) {
            return true;
        }
        if (!kickAll()) {
            return false;
        }
        return Bukkit.unloadWorld(world,true);
    }

    /**
     * Attempts to softly unload the world.
     * <p>
     * The key difference between this and {@link #unload()} is that it will
     * not unload a world if a player is present in it.
     *
     * @return {@code true} if the world was successfully unloaded or was already unloaded,
     *         {@code false} if there are players present in the associated world or another unload problem occurs.
     */
    public boolean softUnload() {
        if (!isLoaded()) {
            return true;
        }
        if (!world.getPlayers().isEmpty()) {
            return false;
        }
        return unload();
    }

    public boolean delete() {
        if (unload()) {
            return FileUtils.delete(getFolder());
        }
        return false;
    }

    public boolean kickAll() {
        load();
        world.getPlayers().forEach(player -> {
            VoyageAPI.evacuate(player, world.getName());
        });
        return (world.getPlayers().isEmpty());
    }

    public World getWorld() {
        if (!(isLoaded())) {
            load();
        }
        // Loaded previously
        if (world == null) {
            world = Bukkit.getWorld(getName());
        }
        return world;
    }

    public File getFolder() {
        return new File(Bukkit.getWorldContainer()+File.separator+getName());
    }

    public String getName() {
        return name;
    }

    public boolean isLoaded() {
        World worldLookup = Bukkit.getWorld(name);
        return (worldLookup != null);
    }

    @Override
    public @NotNull Map<String, Object> serialize() {
        LinkedHashMap<String, Object> map = new LinkedHashMap<>();
        map.put("name",name);
        map.put("load-on-start",loadOnStart);
        map.put("generator",generator);
        if (buffetBiomeName != null) {
            map.put("buffet", buffetBiomeName);
        }
        return map;
    }

    public VoyageWorld deserialize(Map<String, Object> map) {
        // Check if there is anything to load.
        map.forEach((k,v) -> {
            System.out.println("KEY: "+k.toString()+" | V: "+v);
        });
        if (map.size() <= 1) {
            System.out.println("Nothing to deserialize for this Voyage World!");
            return this;
        }
        if (map.containsKey("name")) {
            if (this.name == null) {
                this.name = (String) map.get("name");
            }
        }
        if (map.containsKey("keep-loaded")) {
            this.loadOnStart = (boolean) map.get("load-on-start");
        }
        if (map.containsKey("generator")) {
            this.setGenerator((String) map.get("generator"));
        }
        if (map.containsKey("buffet")) {
            this.worldCreator.biomeProvider(getSingleBiomeProvider((String) map.get("buffet")));
        }
        return this;
    }

    public BoostedConfig getConfig() {
        if (config == null) {
            this.config = new BoostedConfig(new File(Bukkit.getWorldContainer()+File.separator+name),"voyage", null, SpigotSerializer.getInstance());
        }
        return config;
    }

    public void serializeAndSaveConfig() {
        getConfig();
        serialize().forEach((s,o) -> {
            config.get().set(Route.from(s),o);
        });
        config.save();
        System.out.println("Config saved for "+name);
    }
}
