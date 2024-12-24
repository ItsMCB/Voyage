package me.itsmcb.voyage.features.world;

import me.itsmcb.vexelcore.bukkit.api.command.CustomCommand;
import me.itsmcb.vexelcore.bukkit.api.text.BukkitMsgBuilder;
import me.itsmcb.vexelcore.bukkit.api.utils.Msg;
import me.itsmcb.vexelcore.bukkit.api.utils.WorldUtils;
import me.itsmcb.vexelcore.common.api.command.CMDHelper;
import me.itsmcb.vexelcore.common.api.utils.FileUtils;
import me.itsmcb.vexelcore.common.api.utils.StringUtils;
import me.itsmcb.voyage.Voyage;
import me.itsmcb.voyage.api.VoyageAPI;
import me.itsmcb.voyage.api.VoyageWorld;
import org.bukkit.World;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Player;

import java.text.DecimalFormat;
import java.util.List;

public class InfoCmd extends CustomCommand {

    private Voyage instance;

    public InfoCmd(Voyage instance) {
        super("info", "View information about a world","voyage.admin");
        this.instance = instance;
    }

    @Override
    public void executeAsPlayer(Player player, String[] args) {
        VoyageWorld voyageWorld = new VoyageWorld(player.getWorld());
        CMDHelper cmdHelper = new CMDHelper(args);
        if (cmdHelper.argExists(0)) {
            voyageWorld = new VoyageWorld(args[0]);
            voyageWorld.load();
        }
        World world = voyageWorld.getWorld();
        Msg.send(player,
                instance.getLocalizationManager().get("bar", instance.getLocalizationManager().get("world.header-title")),
                instance.getLocalizationManager().get("result", instance.getLocalizationManager().get("world.info.name"), world.getName()),
                instance.getLocalizationManager().get("result", instance.getLocalizationManager().get("world.info.environment"), world.getEnvironment().name()),
                instance.getLocalizationManager().get("result", instance.getLocalizationManager().get("world.info.generator"), (world.getGenerator() == null) ? "Default" : world.getGenerator().getClass().getSimpleName()),
                instance.getLocalizationManager().get("result", instance.getLocalizationManager().get("world.info.difficulty"), world.getDifficulty().name()),
                instance.getLocalizationManager().get("result", instance.getLocalizationManager().get("world.info.loaded-chunks"), world.getLoadedChunks().length+""),
                instance.getLocalizationManager().get("result", instance.getLocalizationManager().get("world.info.total-chunks"), world.getChunkCount()+""),
                instance.getLocalizationManager().get("result",  instance.getLocalizationManager().get("world.info.total-entities"), world.getEntities().size()+""),
                instance.getLocalizationManager().get("result", instance.getLocalizationManager().get("world.info.total-tile-entities"), world.getTileEntityCount()+""),
                instance.getLocalizationManager().get("result", instance.getLocalizationManager().get("world.info.total-living-entities"), StringUtils.getCommaString(world.getLivingEntities().stream().map(e -> e.getType().getKey().value()).toList())),
                instance.getLocalizationManager().get("result", instance.getLocalizationManager().get("world.info.players"), world.getPlayerCount()+""),
                instance.getLocalizationManager().get("result", instance.getLocalizationManager().get("world.info.time"), WorldUtils.getTimeAsName(world)),
                instance.getLocalizationManager().get("result", instance.getLocalizationManager().get("world.info.border-size"), world.getWorldBorder().getSize()+"")
        );
        // todo data folder + world
        new BukkitMsgBuilder("&3Usage: &b"+ FileUtils.getRecursiveFileSizeFormatted(voyageWorld.getWorld().getWorldFolder())).send(player);
    }

    @Override
    public List<String> getAdditionalCompletions(CommandSender sender) {
        return VoyageAPI.allWorldFolderNames().stream().map(s -> "\""+s+"\"").toList();
    }
}
