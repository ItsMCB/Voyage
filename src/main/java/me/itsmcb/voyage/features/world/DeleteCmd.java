package me.itsmcb.voyage.features.world;

import me.itsmcb.vexelcore.bukkit.api.command.CustomCommand;
import me.itsmcb.vexelcore.bukkit.api.text.BukkitMsgBuilder;
import me.itsmcb.vexelcore.common.api.command.CMDHelper;
import me.itsmcb.vexelcore.common.api.utils.FileUtils;
import me.itsmcb.voyage.Voyage;
import me.itsmcb.voyage.api.VoyageAPI;
import me.itsmcb.voyage.api.VoyageWorld;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Player;

import java.io.File;
import java.util.List;

public class DeleteCmd extends CustomCommand {

    private Voyage instance;

    public DeleteCmd(Voyage instance) {
        super("delete", "Delete world folder and file data","voyage.admin");
        this.instance = instance;
    }

    @Override
    public void executeAsPlayer(Player player, String[] args) {
        if (args.length == 0) {
            help(player);
            return;
        }
        // Check confirmation
        CMDHelper cmdHelper = new CMDHelper(args);
        if (!cmdHelper.argEquals(1,"--confirm")) {
            new BukkitMsgBuilder(instance.getLocalizationManager().getWithPrefix("world.deletion-confirmation")).send(player);
            return;
        }
        VoyageWorld voyageWorld = new VoyageWorld(args[0]);
        // Load temporarily to get folder
        voyageWorld.unload();
        // delete files
        if (!voyageWorld.delete()) {
            new BukkitMsgBuilder(instance.getLocalizationManager().getWithPrefix("world.deletion-error")).send(player);
            return;
        }
        new BukkitMsgBuilder(instance.getLocalizationManager().getWithPrefix("world.deletion-complete")).send(player);
    }

    @Override
    public List<String> getAdditionalCompletions(CommandSender sender) {
        return VoyageAPI.allWorldFolderNames().stream().map(s -> "\""+s+"\"").toList();
    }
}
