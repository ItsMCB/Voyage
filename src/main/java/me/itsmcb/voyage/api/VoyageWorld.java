package me.itsmcb.voyage.api;

import libs.dev.dejvokep.boostedyaml.spigot.SpigotSerializer;
import me.itsmcb.vexelcore.common.api.config.BoostedConfig;
import org.bukkit.Bukkit;
import org.bukkit.World;
import org.bukkit.WorldCreator;
import org.bukkit.WorldType;
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
    private World world = null;

    private BoostedConfig config;

    public VoyageWorld(String name) {
        this.name = name;
        this.worldCreator = new WorldCreator(name);
    }

    public VoyageWorld(World world) {
        this.name = world.getName();
        this.world = world;
    }

    public void setSeed(Long seed) {
        worldCreator.seed(seed);
    }

    public void setWorldType(WorldType worldType) {
        worldCreator.type(worldType);
    }

    public void setEnvironment(World.Environment environment) {
        worldCreator.environment(environment);
    }

    public void setGenerator(String input) {
        // World types may be passed as a generator so Voyage will accommodate for that.
        if (input.equalsIgnoreCase("flat")) {
            setWorldType(WorldType.FLAT);
            return;
        }
        if (input.equalsIgnoreCase("amplified")) {
            setWorldType(WorldType.AMPLIFIED);
            return;
        }
        if (input.equalsIgnoreCase("largebiomes") || input.equalsIgnoreCase("large biomes")) {
            setWorldType(WorldType.LARGE_BIOMES);
            return;
        }
        if (input.equalsIgnoreCase("normal")) {
            setWorldType(WorldType.NORMAL);
            return;
        }
        worldCreator.generator(input);
        worldCreator.generatorSettings();
    }

    public boolean load() {
        if (world == null) {
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
        return (world.getPlayers().size() == 0);
    }

    public World getWorld() {
        load();
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
        map.put("keep-loaded",true);
        return map;
    }

    public void createConfig(File saveLocation) {
        this.config = new BoostedConfig(saveLocation,"worlds"+ File.separator + name, null, new SpigotSerializer());
        config.get().set("data",serialize());
        config.save();
    }

    public void setConfig(BoostedConfig config) {
        this.config = config;
    }

    public BoostedConfig getConfig() {
        return config;
    }
}
