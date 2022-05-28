package me.itsmcb.voyage.commands;

import me.itsmcb.voyage.Voyage;
import me.itsmcb.voyage.vexelcoreapitemp.BukkitUtils;
import me.itsmcb.voyage.vexelcoreapitemp.CMDHelper;
import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.World;
import org.bukkit.command.Command;
import org.bukkit.command.CommandExecutor;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Player;
import org.jetbrains.annotations.NotNull;

public class WorldCMD implements CommandExecutor {

    private Voyage instance;

    public WorldCMD(Voyage instance) {
        this.instance = instance;
    }

    @Override
    public boolean onCommand(@NotNull CommandSender sender, @NotNull Command command, @NotNull String label, @NotNull String[] args) {
        if (!sender.hasPermission("voyage.admin")) {
            BukkitUtils.send(sender, "&cOops! You lack permission to do this. Admins, give yourself the following permission node to unlock ability: voyage.admin");
            return true;
        }
        CMDHelper cmdHelper = new CMDHelper(args);
        if (cmdHelper.argExists(0)) {
            // Validation of world
            World world = Bukkit.getWorld(args[0]);
            if (world == null) {
                BukkitUtils.send(sender, "&cInvalid world!");
                return false;
            }
            // Check if calling valid command
            if (cmdHelper.argEquals(1,"info")) {
                BukkitUtils.send(sender,
                        "&7========== &6 World Information &7 ==========",
                        formatInformation("Name", world.getName()),
                        formatInformation("Environment", world.getEnvironment().name()),
                        formatInformation("Difficulty", world.getDifficulty().name()),
                        formatInformation("Loaded chunks", world.getLoadedChunks().length+""),
                        formatInformation("Chunk count", world.getChunkCount()+""),
                        formatInformation("Total Entities", world.getEntities().size()+""),
                        formatInformation("Tile Entities", world.getTileEntityCount()+""),
                        formatInformation("Players", world.getPlayerCount()+""),
                        formatInformation("Time", world.getTime()+""),
                        formatInformation("Border Size", world.getWorldBorder().getSize()+"")
                );
                sender.sendMessage(BukkitUtils.interactiveMessageCopyContent(formatInformation("Seed", String.valueOf(world.getSeed())),"&7Click to copy seed", String.valueOf(world.getSeed())));
                Location spawnLocation = world.getSpawnLocation();
                int x = spawnLocation.getBlockX();
                int y = spawnLocation.getBlockY();
                int z = spawnLocation.getBlockZ();
                sender.sendMessage(BukkitUtils.interactiveMessageRunCommand(formatInformation("Spawn", x+" "+y+" "+z),"&7Click to teleport","/world teleport " + world.getName()));
                return true;
            }
            if (cmdHelper.argEquals(1,"create")) {
                if (cmdHelper.argExists(2)) {
                    // world name
                    return true;
                }
                BukkitUtils.send(sender, "Create world txt");
            }
            if (cmdHelper.argEquals(1,"delete")) {
                BukkitUtils.send(sender, "Delete world txt");
            }
            if (cmdHelper.argEquals(1,"import") || cmdHelper.argEquals(1,"load")) {
                BukkitUtils.send(sender, "Import/load world txt");
            }
            if (cmdHelper.argEquals(1,"delete")) {
                BukkitUtils.send(sender, "Delete world txt");
            }
            if (cmdHelper.argEquals(1,"option")) {
                // option <world name> <option> <value>
                BukkitUtils.send(sender, "Delete world txt");
            }
            if (cmdHelper.argEquals(1,"teleport")) {
                if (sender instanceof Player player) {
                    Location spawnLocation = world.getSpawnLocation();
                    int x = spawnLocation.getBlockX();
                    int y = spawnLocation.getBlockY();
                    int z = spawnLocation.getBlockZ();
                    player.teleport(spawnLocation);
                    BukkitUtils.send(player, "");

                }
            }
            if (cmdHelper.argEquals(1,"border")) {
                world.getWorldBorder().setSize(Integer.parseInt(args[2]));
                sender.sendMessage("Set world border to " + args[2]);
            }
            return true;
        }
        // Send command help
        BukkitUtils.send(sender,
                "&7========== &6 World Command Help &7 ==========",
                formatInformation("/world create <world name> <environment type> <generator>", "Creates new work with set parameters"),
                formatInformation("/world delete <world name>", "Delete a world"),
                formatInformation("/world info <world name>", "View information about a world"),
                formatInformation("/world import <world name>", "Loads world and creates editable configuration"),
                formatInformation("/world load", "Temporarily import a world until the next server restart"),
                formatInformation("/world teleport <world name>", "Teleport to world spawn"),
                formatInformation("/world option <world name> <key> <value>", "Configure world options")
        );
        return false;
    }

    private String formatInformation(String key, String value) {
        return "&3"+key+"&7: &b"+value;
    }

}
