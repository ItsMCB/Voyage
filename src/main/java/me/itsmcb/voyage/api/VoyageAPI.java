package me.itsmcb.voyage.api;

import me.itsmcb.vexelcore.bukkit.api.text.BukkitMsgBuilder;
import me.itsmcb.vexelcore.common.api.utils.FileUtils;
import org.bukkit.Bukkit;
import org.bukkit.World;
import org.bukkit.WorldCreator;
import org.bukkit.WorldType;
import org.bukkit.entity.Player;
import org.bukkit.generator.ChunkGenerator;
import org.bukkit.plugin.Plugin;

import java.io.File;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.Random;

public class VoyageAPI {

    /**
     * Find the Minecraft seed associated with a string input.
     * This matches vanilla Minecraft behavior, such as from the world creation seed section.
     *
     * @param input The string to be converted into a seed.
     * @return A long representing the world seed.
     */
    public static long generateSeedFromString(String input) {
        if (input == null || input.isEmpty()) {
            return new Random().nextLong(); // If there is no input, pick a random seed. This matches vanilla behavior.
        }
        try {
            return Long.parseLong(input); // If already in the long seed format, pass it through.
        } catch (NumberFormatException e) { // String conversion necessary
            return input.hashCode();
        }
    }

    public static boolean isEnvironment(String input) {
        try {
            WorldCreator worldCreator = new WorldCreator("voyage-api-world");
            World.Environment worldEnvironment = World.Environment.valueOf(input);
            worldCreator.environment(worldEnvironment);
            return true;
        } catch (IllegalArgumentException e) {
            return false;
        }
    }

    public static World.Environment getEnvironment(String input) {
        if (isEnvironment(input)) {
            return World.Environment.valueOf(input);
        }
        return World.Environment.NORMAL;
    }

    @Deprecated
    public static void evacuate(Player player, String from) {
        evacuate(player);
    }

    public static void evacuate(Player player) {
        World evacuationWorld = Bukkit.getWorlds().stream().filter(world -> world != player.getWorld()).findFirst().orElse(null);
        if (evacuationWorld == null) {
            player.kick(new BukkitMsgBuilder("&cYou have been kicked because the only loaded world is being unloaded").get());
            return;
        }
        player.teleport(evacuationWorld.getSpawnLocation());
    }

    public static ArrayList<String> allWorldFolderNames() {
        ArrayList<String> worldNames = new ArrayList<>();
        for (File file : Objects.requireNonNull(Bukkit.getWorldContainer().listFiles())) {
            if (file.isDirectory()) {
                File regionFolder = new File(file.getPath()+File.separator+"region");
                File uidDat = new File(file.getPath()+File.separator+"uid.dat");
                if (regionFolder.exists() || uidDat.exists()) {
                    worldNames.add(file.getName());
                }
            }
        }
        return worldNames;
    }

    public static List<World> loadedWorlds() {
        return Bukkit.getWorlds();
    }

    public static List<String> unloadedWorlds() {
        ArrayList<String> allWorldNames = allWorldFolderNames();
        allWorldNames.removeAll(loadedWorlds().stream().map(World::getName).toList());
        return allWorldNames;
    }

    // Generator, Author
    public static List<VoyageWorldGenerator> getGenerators() {
        ArrayList<VoyageWorldGenerator> generators = new ArrayList<>();
        for (WorldType worldType : WorldType.values()) {
            VoyageWorldGenerator generator = new VoyageWorldGenerator(worldType.getName(),List.of("Mojang"), VoyageWorldGenerator.Origin.MOJANG);
            generators.add(generator);
        }
        for(Plugin plugin : Bukkit.getPluginManager().getPlugins()) {
            ChunkGenerator chunkGenerator = plugin.getDefaultWorldGenerator("testworld", null);
            if (chunkGenerator != null) {
                VoyageWorldGenerator generator = new VoyageWorldGenerator(plugin.getName(),plugin.getDescription().getAuthors(), VoyageWorldGenerator.Origin.PLUGIN);
                generators.add(generator);
            }
        }
        return generators.stream().toList();
    }


}
