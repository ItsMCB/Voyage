package me.itsmcb.voyage.features.world;

import me.itsmcb.vexelcore.bukkit.api.command.CustomCommand;
import me.itsmcb.vexelcore.bukkit.api.text.BukkitMsgBuilder;
import me.itsmcb.vexelcore.common.api.command.CMDHelper;
import me.itsmcb.vexelcore.common.api.utils.FileUtils;
import me.itsmcb.voyage.Voyage;
import me.itsmcb.voyage.api.VoyageAPI;
import me.itsmcb.voyage.api.VoyageWorld;
import me.itsmcb.voyage.api.VoyageWorldCreator;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Player;

import java.io.File;
import java.io.IOException;
import java.util.List;

public class CopySCmd extends CustomCommand {

    private Voyage instance;

    public CopySCmd(Voyage instance) {
        super("copy", "Copy world","voyage.admin");
        this.instance = instance;
    }

    @Override
    public void executeAsPlayer(Player player, String[] args) {
        if (args.length < 2) {
            help(player);
            return;
        }
        if (!(VoyageAPI.allWorldFolderNames().stream().map(s -> s.toLowerCase()).toList().contains(args[0].toLowerCase()))) {
            new BukkitMsgBuilder(instance.getLocalizationManager().getWithPrefix("world.does-not-exist")).send(player);
        }
        VoyageWorld sourceWorld = new VoyageWorld(args[0]);
        System.out.println(args[0]);
        // Unload to prevent errors and corruption
        if (sourceWorld.isLoaded()) {
            sourceWorld.getWorld().save();
            sourceWorld.unload();
        }
        // Copy to new folder and teleport
        try {
            VoyageWorld createdWorld = VoyageWorldCreator.copyFromSource(sourceWorld.getFolder(),args[1]);
            new BukkitMsgBuilder(instance.getLocalizationManager().getWithPrefix("world.creation-success")).send(player);
            createdWorld.load();
            player.teleport(createdWorld.getWorld().getSpawnLocation());
        } catch (IOException e) {
            throw new RuntimeException(e);
        }
    }

    @Override
    public List<String> getAdditionalCompletions(CommandSender sender) {
        return VoyageAPI.allWorldFolderNames().stream().map(s -> "\""+s+"\"").toList();
    }
}
