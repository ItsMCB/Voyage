package me.itsmcb.voyage.features.world;

import me.itsmcb.vexelcore.bukkit.api.experience.AudioResponse;
import me.itsmcb.vexelcore.bukkit.api.text.BukkitMsgBuilder;
import me.itsmcb.vexelcore.bukkit.api.utils.FileUtils;
import me.itsmcb.vexelcore.bukkit.api.utils.Msg;
import me.itsmcb.vexelcore.bukkit.api.utils.WorldUtils;
import me.itsmcb.vexelcore.common.api.command.CMDHelper;
import me.itsmcb.vexelcore.common.api.utils.StringUtils;
import me.itsmcb.voyage.Voyage;
import me.itsmcb.voyage.api.VoyageWorld;
import net.kyori.adventure.text.event.ClickEvent;
import org.bukkit.*;
import org.bukkit.command.Command;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Player;
import org.bukkit.generator.ChunkGenerator;
import org.bukkit.plugin.Plugin;
import org.jetbrains.annotations.NotNull;

public class WorldCMD extends Command {

    private Voyage instance;

    public WorldCMD(Voyage instance) {
        super("world");
        this.instance = instance;
    }

    @Override
    public boolean execute(@NotNull CommandSender sender, @NotNull String commandLabel, @NotNull String[] args) {
        if (!sender.hasPermission("voyage.admin")) {
            Msg.sendResponsive(AudioResponse.ERROR, sender, instance.getLocalizationManager().getWithPrefix("error-permission"));
            return true;
        }
        CMDHelper cmdHelper = new CMDHelper(args);
        // Subcommands that require a specific world
        if (cmdHelper.argExists(1)) {
            // Eventually make a validation method
            // also check if world already exists
            String validWorldName = args[1];
            VoyageWorld voyageWorld = new VoyageWorld(validWorldName);
            // Create
            if (cmdHelper.argEquals(0,"create")) {
                WorldCreator worldCreator = new WorldCreator(validWorldName);
                String seed = cmdHelper.getFlag("--seed");
                if (seed != null) {
                    try {
                        worldCreator.seed(Long.parseLong(seed));
                    } catch (NumberFormatException e) {
                        Msg.sendResponsive(AudioResponse.ERROR, sender, instance.getLocalizationManager().getWithPrefix("world.creation-error-seed"));
                        return true;
                    }
                }
                String type = cmdHelper.getFlag("--type");
                if (type != null) {
                    WorldType worldType = WorldType.getByName(type);
                    if (worldType == null) {
                        Msg.sendResponsive(AudioResponse.ERROR, sender, instance.getLocalizationManager().getWithPrefix("world.creation-error-type"));
                        return true;
                    }
                    worldCreator.type(WorldType.getByName(type));
                }
                String environment = cmdHelper.getFlag("--environment");
                if (environment != null && !(environment.equalsIgnoreCase("custom"))) {
                    try {
                        worldCreator.environment(World.Environment.valueOf(environment));
                    } catch (IllegalArgumentException e) {
                        Msg.sendResponsive(AudioResponse.ERROR, sender, instance.getLocalizationManager().getWithPrefix("world.creation-error-environment"));
                        return true;
                    }
                }
                String generator = cmdHelper.getFlag("--generator");
                if (generator != null) {
                    worldCreator.generator(generator);
                }
                new BukkitMsgBuilder(instance.getLocalizationManager().getWithPrefix("world.creation-creating")).send(sender);
                worldCreator.createWorld();
                Msg.sendResponsive(AudioResponse.SUCCESS, sender, instance.getLocalizationManager().getWithPrefix("world.creation-success"));
                teleportToWorldSpawn(validWorldName, sender);
                return true;
            }
            // Load
            if (cmdHelper.argEquals(0, "load")) {
                if (voyageWorld.load()) {
                    teleportToWorldSpawn(validWorldName, sender);
                    Msg.sendResponsive(AudioResponse.SUCCESS, sender, instance.getLocalizationManager().getWithPrefix("world.load.success"));
                } else {
                    Msg.sendResponsive(AudioResponse.ERROR, sender, instance.getLocalizationManager().getWithPrefix("world.load.failure"));
                }
                return true;
            }


            // Validate world before continuing
            if (!(voyageWorld.exists())) {
                new BukkitMsgBuilder(instance.getLocalizationManager().getWithPrefix("world.does-not-exist")).send(sender);
                return true;
            }


            // Delete
            if (cmdHelper.argEquals(0, "delete")) {
                if (cmdHelper.argEquals(2, "--confirm")) {
                    if (voyageWorld.isLoaded()) {
                        voyageWorld.unload();
                    }
                    if (FileUtils.deleteFile(voyageWorld.getFolder())) {
                        new BukkitMsgBuilder(instance.getLocalizationManager().getWithPrefix("world.deletion-complete")).send(sender);
                        return true;
                    }
                    new BukkitMsgBuilder(instance.getLocalizationManager().getWithPrefix("world.deletion-error")).send(sender);
                } else {
                    new BukkitMsgBuilder(instance.getLocalizationManager().getWithPrefix("world.deletion-confirmation")).send(sender);
                }
                return true;
            }
            // Teleport
            if (cmdHelper.argEquals(0, "teleport", "tp")) {
                if (sender instanceof Player player) {
                    player.teleport(voyageWorld.getWorld().getSpawnLocation());
                    Msg.send(player, "&3Teleported to &b" + voyageWorld.getName());
                    return true;
                }
            }
            // Info
            if (cmdHelper.argEquals(0, "info")) {
                sendWorldInformation(sender, voyageWorld.getWorld());
                return true;
            }
            // Set Border
            if (cmdHelper.argEquals(0, "border")) {
                // todo Check if arg is int or not
                voyageWorld.setBorderSize(Integer.parseInt(args[2]));
                Msg.sendResponsive(AudioResponse.SUCCESS, sender, instance.getLocalizationManager().getWithPrefix("world.configure-border-success", args[2]));
                return true;
            }
            // Kick All Players
            if (cmdHelper.argEquals(0, "kickall")) {
                if (voyageWorld.kickAll()) {
                    Msg.sendResponsive(AudioResponse.SUCCESS, sender, instance.getLocalizationManager().getWithPrefix("world.kick.success"));
                } else {
                    Msg.sendResponsive(AudioResponse.ERROR, sender, instance.getLocalizationManager().getWithPrefix("world.kick.failure"));
                }
                return true;
            }
        }
        // Subcommands that don't require a specific world

        if (cmdHelper.argExists(0)) {
            // List worlds
            if (cmdHelper.argEquals(0, "list")) {
                new BukkitMsgBuilder(instance.getLocalizationManager().get("bar", instance.getLocalizationManager().get("world.list.header-title"))).send(sender);
                WorldUtils.getLoadedWorlds().forEach(world -> {
                    new BukkitMsgBuilder(instance.getLocalizationManager().get("world.list.entry-loaded", world.getName()))
                            .hover(instance.getLocalizationManager().get("world.list.click-to-teleport"))
                            .clickEvent(ClickEvent.Action.RUN_COMMAND, "/world teleport " + world.getName())
                            .send(sender);
                });
                WorldUtils.getUnloadedWorldNames().forEach(worldName -> {
                    new BukkitMsgBuilder(instance.getLocalizationManager().get("world.list.entry-unloaded", worldName))
                            .hover(instance.getLocalizationManager().get("world.list.click-to-load"))
                            .clickEvent(ClickEvent.Action.RUN_COMMAND, "/world load " + worldName)
                            .send(sender);
                });
                return true;
            }
            // List generators
            if (cmdHelper.argEquals(0,"generators")) {
                new BukkitMsgBuilder( instance.getLocalizationManager().get("world.generators.header")).send(sender);
                for(Plugin plugin : Bukkit.getPluginManager().getPlugins()) {
                    // Check if plugin provides any generators
                    ChunkGenerator generator = plugin.getDefaultWorldGenerator("testworld", null);
                    if (generator != null) {
                        String gauthors = StringUtils.getCommaString(plugin.getDescription().getAuthors());
                        new BukkitMsgBuilder( instance.getLocalizationManager().get("world.generators.entry", plugin.getName(), gauthors)).send(sender);
                    }
                }
                return true;
            }
            if (cmdHelper.argEquals(0, "import") || cmdHelper.argEquals(1, "load")) {
                Msg.send(sender, "Import/load world txt");
                return true;
            }
        }

        Msg.send(sender,
                instance.getLocalizationManager().get("bar", instance.getLocalizationManager().get("world.help.header-title")),
                instance.getLocalizationManager().get("result", instance.getLocalizationManager().get("world.help.create.syntax"), instance.getLocalizationManager().get("world.help.create.description")),
                instance.getLocalizationManager().get("result", instance.getLocalizationManager().get("world.help.delete.syntax"), instance.getLocalizationManager().get("world.help.delete.description")),
                instance.getLocalizationManager().get("result", instance.getLocalizationManager().get("world.help.info.syntax"), instance.getLocalizationManager().get("world.help.info.description")),
                instance.getLocalizationManager().get("result", instance.getLocalizationManager().get("world.help.import.syntax"), instance.getLocalizationManager().get("world.help.import.description")),
                instance.getLocalizationManager().get("result", instance.getLocalizationManager().get("world.help.load.syntax"), instance.getLocalizationManager().get("world.help.load.description")),
                instance.getLocalizationManager().get("result", instance.getLocalizationManager().get("world.help.teleport.syntax"), instance.getLocalizationManager().get("world.help.teleport.description")),
                instance.getLocalizationManager().get("result", instance.getLocalizationManager().get("world.help.border.syntax"), instance.getLocalizationManager().get("world.help.border.description")),
                instance.getLocalizationManager().get("result", instance.getLocalizationManager().get("world.help.list.syntax"), instance.getLocalizationManager().get("world.help.list.description"))
        );
        return false;
    }

    private void teleportToWorldSpawn(String worldName, CommandSender sender) {
        if (sender instanceof Player player) {
            player.teleport(Bukkit.getWorld(worldName).getSpawnLocation());
            // World tp msg
        }
    }

    public void sendWorldInformation(CommandSender sender, World world) {
        Msg.send(sender,
                instance.getLocalizationManager().get("bar", instance.getLocalizationManager().get("world.header-title")),
                instance.getLocalizationManager().get("result", instance.getLocalizationManager().get("world.info.name"), world.getName()),
                instance.getLocalizationManager().get("result", instance.getLocalizationManager().get("world.info.environment"), world.getEnvironment().name()),
                instance.getLocalizationManager().get("result", instance.getLocalizationManager().get("world.info.generator"), (world.getGenerator() == null) ? "Default" : world.getGenerator().getClass().getSimpleName()),
                instance.getLocalizationManager().get("result", instance.getLocalizationManager().get("world.info.difficulty"), world.getDifficulty().name()),
                instance.getLocalizationManager().get("result", instance.getLocalizationManager().get("world.info.loaded-chunks"), world.getLoadedChunks().length+""),
                instance.getLocalizationManager().get("result", instance.getLocalizationManager().get("world.info.total-chunks"), world.getChunkCount()+""),
                instance.getLocalizationManager().get("result",  instance.getLocalizationManager().get("world.info.total-entities"), world.getEntities().size()+""),
                instance.getLocalizationManager().get("result", instance.getLocalizationManager().get("world.info.total-tile-entities"), world.getTileEntityCount()+""),
                instance.getLocalizationManager().get("result", instance.getLocalizationManager().get("world.info.players"), world.getPlayerCount()+""),
                instance.getLocalizationManager().get("result", instance.getLocalizationManager().get("world.info.time"), world.getTime()+""),
                instance.getLocalizationManager().get("result", instance.getLocalizationManager().get("world.info.border-size"), world.getWorldBorder().getSize()+"")
        );
        /*
        sender.sendMessage(Msg.copyContent(FormatUtils.format("Seed", String.valueOf(world.getSeed())), "&7Click to copy seed", String.valueOf(world.getSeed())));
        Location spawnLocation = world.getSpawnLocation();
        int x = spawnLocation.getBlockX();
        int y = spawnLocation.getBlockY();
        int z = spawnLocation.getBlockZ();
        sender.sendMessage(Msg.runCommand(FormatUtils.format("Spawn", x + " ; " + y + " ; " + z), "&7Click to teleport", "/world teleport " + world.getName()));

         */
    }

}
