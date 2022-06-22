package me.itsmcb.voyage.commands;

import me.itsmcb.vexelcore.bukkit.api.experience.AudioResponse;
import me.itsmcb.vexelcore.bukkit.api.utils.Msg;
import me.itsmcb.vexelcore.common.api.command.CMDHelper;
import me.itsmcb.voyage.FormatUtils;
import me.itsmcb.voyage.Voyage;
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
            Msg.sendResponsive(AudioResponse.ERROR, sender, "&cOops! You lack permission to do this. To use, give yourself the following permission: voyage.admin");
            return true;
        }
        CMDHelper cmdHelper = new CMDHelper(args);
        if (cmdHelper.argExists(0)) {
            // Ensure world is selected
            World world;
            if (cmdHelper.argExists(1)) {
                world = Bukkit.getWorld(args[1]);
            } else {
                if (sender instanceof Player player) {
                    world = player.getWorld();
                } else {
                    Msg.send(sender, "&cMust include world name");
                    return true;
                }
            }
            // Ensure world exists
            if (world == null) {
                Msg.send(sender, "&cInvalid world!");
                return false;
            }
            // Check if calling valid command
            if (cmdHelper.argEquals(0,"info")) {
                Msg.send(sender,
                        "&7========== &6 World Information &7 ==========",
                        FormatUtils.format("Name", world.getName()),
                        FormatUtils.format("Environment", world.getEnvironment().name()),
                        FormatUtils.format("Difficulty", world.getDifficulty().name()),
                        FormatUtils.format("Loaded chunks", world.getLoadedChunks().length+""),
                        FormatUtils.format("Chunk count", world.getChunkCount()+""),
                        FormatUtils.format("Total Entities", world.getEntities().size()+""),
                        FormatUtils.format("Tile Entities", world.getTileEntityCount()+""),
                        FormatUtils.format("Players", world.getPlayerCount()+""),
                        FormatUtils.format("Time", world.getTime()+""),
                        FormatUtils.format("Border Size", world.getWorldBorder().getSize()+"")
                );
                sender.sendMessage(Msg.copyContent(FormatUtils.format("Seed", String.valueOf(world.getSeed())),"&7Click to copy seed", String.valueOf(world.getSeed())));
                Location spawnLocation = world.getSpawnLocation();
                int x = spawnLocation.getBlockX();
                int y = spawnLocation.getBlockY();
                int z = spawnLocation.getBlockZ();
                sender.sendMessage(Msg.runCommand(FormatUtils.format("Spawn", x+" ; "+y+" ; "+z),"&7Click to teleport","/world teleport " + world.getName()));
                return true;
            }
            if (cmdHelper.argEquals(0,"create")) {
                if (cmdHelper.argExists(2)) {
                    // world name
                    return true;
                }
                Msg.send(sender, "Create world txt");
            }
            if (cmdHelper.argEquals(0,"delete")) {
                Msg.send(sender, "Delete world txt");
            }
            if (cmdHelper.argEquals(0,"import") || cmdHelper.argEquals(1,"load")) {
                Msg.send(sender, "Import/load world txt");
            }
            if (cmdHelper.argEquals(0,"delete")) {
                Msg.send(sender, "Delete world txt");
            }
            if (cmdHelper.argEquals(0,"option")) {
                // option <world name> <option> <value>
                Msg.send(sender, "Delete world txt");
            }
            if (cmdHelper.argEquals(0,"teleport","tp")) {
                if (sender instanceof Player player) {
                    Location spawnLocation = world.getSpawnLocation();
                    int x = spawnLocation.getBlockX();
                    int y = spawnLocation.getBlockY();
                    int z = spawnLocation.getBlockZ();
                    player.teleport(spawnLocation);
                    Msg.send(player, "&3Teleported to &b" + world.getName());

                }
            }
            if (cmdHelper.argEquals(0,"border")) {
                world.getWorldBorder().setSize(Integer.parseInt(args[2]));
                sender.sendMessage("Set world border to " + args[2]);
            }
            return true;
        }
        // Send command help
        Msg.send(sender,
                "&7========== &6 World Command Help &7 ==========",
                FormatUtils.format("/world create <world name> <environment type> <generator>", "Creates new work with set parameters"),
                FormatUtils.format("/world delete <world name>", "Delete a world"),
                FormatUtils.format("/world info <world name>", "View information about a world"),
                FormatUtils.format("/world import <world name>", "Loads world and creates editable configuration"),
                FormatUtils.format("/world load", "Temporarily import a world until the next server restart"),
                FormatUtils.format("/world teleport <world name>", "Teleport to world spawn"),
                FormatUtils.format("/world option <world name> <key> <value>", "Configure world options")
        );
        return false;
    }

}
