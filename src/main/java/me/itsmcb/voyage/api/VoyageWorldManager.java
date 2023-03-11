package me.itsmcb.voyage.api;

import libs.dev.dejvokep.boostedyaml.spigot.SpigotSerializer;
import me.itsmcb.vexelcore.common.api.config.BoostedConfig;

import java.io.File;
import java.util.Arrays;

public class VoyageWorldManager {

    private File dataFolder;

    public VoyageWorldManager(File dataFolder) {
        this.dataFolder = dataFolder;
    }

    // Test...for deletion when done
    public void test(String name, File file) {
        VoyageWorld world = new VoyageWorld(name);
        world.createConfig(file);
    }
    
    public void loadDesiredWorlds() {
        File worldsFolder = new File(dataFolder + File.separator + "worlds");
        if (!worldsFolder.exists()) {
            return;
        }
        File[] worldConfigs = worldsFolder.listFiles();
        if (worldConfigs == null) {
            return;
        }
        for (File file : Arrays.stream(worldConfigs).toList()) {
            if (file.isDirectory()) {
                return;
            }
            String name = file.getName().substring(0,file.getName().indexOf("."));
            VoyageWorld voyageWorld = (VoyageWorld) saveConfig(name).get().get("data");
            System.out.println("Successfully got " + voyageWorld.getName());
        }
    }

    public BoostedConfig saveConfig(String worldName) {
        BoostedConfig worldConfig = new BoostedConfig(dataFolder,"worlds"+ File.separator + worldName, null, new SpigotSerializer());
        worldConfig.save();
        return worldConfig;
    }

}
