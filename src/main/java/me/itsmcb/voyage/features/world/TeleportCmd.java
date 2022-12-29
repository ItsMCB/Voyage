package me.itsmcb.voyage.features.world;

import me.itsmcb.vexelcore.bukkit.api.command.CustomCommand;
import me.itsmcb.voyage.Voyage;
import me.itsmcb.voyage.api.VoyageAPI;
import me.itsmcb.voyage.api.VoyageWorld;
import org.bukkit.entity.Player;

import java.util.List;

public class TeleportCmd extends CustomCommand {

    private Voyage instance;

    public TeleportCmd(Voyage instance) {
        super("teleport", "Teleport to a world","voyage.admin");
        this.instance = instance;
    }

    @Override
    public void executeAsPlayer(Player player, String[] args) {
        if (args.length == 0) {
            player.sendMessage(help());
            return;
        }
        VoyageWorld voyageWorld = new VoyageWorld(args[0]);
        voyageWorld.load();
        player.teleport(voyageWorld.getWorld().getSpawnLocation());
        // TODO send message
    }

    @Override
    public List<String> getAdditionalCompletions() {
        return VoyageAPI.allWorldFolderNames();
    }
}
