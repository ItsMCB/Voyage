package me.itsmcb.voyage.api;

import org.bukkit.Bukkit;
import org.bukkit.World;
import org.bukkit.WorldCreator;
import org.bukkit.WorldType;

public class VoyageWorld {

    private String name;
    private WorldCreator worldCreator;

    private World world = null;

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
}
