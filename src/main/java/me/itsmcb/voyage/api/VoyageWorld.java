package me.itsmcb.voyage.api;

import libs.dev.dejvokep.boostedyaml.route.Route;
import libs.dev.dejvokep.boostedyaml.spigot.SpigotSerializer;
import me.itsmcb.vexelcore.common.api.config.BoostedConfig;
import org.bukkit.*;
import org.bukkit.configuration.serialization.ConfigurationSerializable;
import org.bukkit.configuration.serialization.SerializableAs;
import org.jetbrains.annotations.NotNull;

import java.io.File;
import java.util.LinkedHashMap;
import java.util.Map;

@SerializableAs("VoyageWorld")
public class VoyageWorld implements ConfigurationSerializable {

    private String name;
    private WorldCreator worldCreator;
    private String generator;
    private World world = null;
    private boolean loadOnStart = false;
    private BoostedConfig config;

    public VoyageWorld(String name) {
        this.name = name;
        this.worldCreator = new WorldCreator(name);
    }

    public VoyageWorld(World world) {
        this.name = world.getName();
        this.world = world;
    }

    public VoyageWorld setName(String name) {
        this.name = name;
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

    public VoyageWorld setGeneratorSettings(String settings) {
        worldCreator.generatorSettings(settings);
        return this;
    }

    public VoyageWorld setDifficulty(Difficulty difficulty) {
        getWorld().setDifficulty(difficulty);
        return this;
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
        worldCreator.generatorSettings();
        return this;
    }

    public void setLoadOnStart(boolean loadOnStart) {
        this.loadOnStart = loadOnStart;
    }

    public boolean load() {
        if (world == null) {
            deserialize(getConfig().get().getStringRouteMappedValues(true));
            System.out.println("LOAD ABOUT TO SAVE BTW");
            serializeAndSaveConfig();
            world = worldCreator.createWorld();
        }
        return (world != null);
    }

    public boolean unload() {
        if (!kickAll()) {
            return false;
        }
        return Bukkit.unloadWorld(world,true);
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
        return world;
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
        System.out.println("Serialize gen: "+generator);
        return map;
    }

    public VoyageWorld deserialize(Map<String, Object> map) {
        // Check if there is anything to load.
        System.out.println("deserialize moment");
        map.forEach((k,v) -> {
            System.out.println("KEY: "+k.toString()+" | V: "+v);
        });
        if (map.size() <= 1) {
            System.out.println("Map size L");
            return this;
        }
        System.out.println("DESERIALIZE START! - "+(String) map.get("name"));
        if (map.containsKey("name")) {
            this.name = (String) map.get("name");
        }
        if (map.containsKey("keep-loaded")) {
            this.loadOnStart = (boolean) map.get("load-on-start");
        }
        if (map.containsKey("generator")) {
            this.setGenerator((String) map.get("generator"));
            System.out.println("OOOOOOOOOOF lol");
        }
        return this;
    }

    public BoostedConfig getConfig() {
        if (config == null) {
            this.config = new BoostedConfig(new File(Bukkit.getWorldContainer()+File.separator+name),"voyage", null, new SpigotSerializer());
        }
        return config;
    }

    public void serializeAndSaveConfig() {
        System.out.println("Saving");
        getConfig();
        serialize().forEach((s,o) -> {
            config.get().set(Route.from(s),o);
        });
        config.save();
        System.out.println("Save done btwz");
    }
}
