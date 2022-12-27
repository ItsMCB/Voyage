package me.itsmcb.voyage.api;

import me.itsmcb.vexelcore.bukkit.api.utils.WorldUtils;
import org.bukkit.Bukkit;
import org.bukkit.World;
import org.bukkit.WorldCreator;
import org.jetbrains.annotations.NotNull;

import java.io.File;

public class VoyageWorld {
    String name;
    World world;

    public VoyageWorld(World world) {
        this.world = world;
        this.name = world.getName();
    }

    public VoyageWorld(String name) {
        this.world = Bukkit.getWorld(name);
        this.name = name;
    }

    public World getWorld() {
        return world;
    }

    public String getName() {
        return name;
    }

    public @NotNull File getFolder() {
        // check if loaded already
        return this.world.getWorldFolder();
    }

    public boolean isLoaded() {
        return WorldUtils.isLoaded(name);
    }

    public boolean unload() {
        return WorldUtils.unloadWorld(name);
    }

    public boolean kickAll() {
        return WorldUtils.kickAllFromWorld(name, WorldUtils.getDefaultWorld());
    }

    public boolean exists() {
        return WorldUtils.exists(name);
    }

    // Loads world IF FOLDER EXISTS
    public boolean load() {
        if (!exists()) {
            return false;
        }
        WorldCreator worldCreator = new WorldCreator(name);
        worldCreator.createWorld();
        return isLoaded();
    }

    public void setBorderSize(double size) {
        world.getWorldBorder().setSize(size);
    }
}
