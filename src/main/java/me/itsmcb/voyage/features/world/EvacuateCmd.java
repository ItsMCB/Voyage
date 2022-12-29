package me.itsmcb.voyage.features.world;

import me.itsmcb.vexelcore.bukkit.api.command.CustomCommand;
import me.itsmcb.vexelcore.common.api.command.CMDHelper;
import me.itsmcb.voyage.Voyage;
import me.itsmcb.voyage.api.VoyageAPI;
import me.itsmcb.voyage.api.VoyageWorld;
import org.bukkit.entity.Player;

import java.util.List;

public class EvacuateCmd extends CustomCommand {

    private Voyage instance;

    public EvacuateCmd(Voyage instance) {
        super("evacuate", "Kick all players out of a world","voyage.admin");
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
        String worldName = voyageWorld.getName();
        voyageWorld.getWorld().getPlayers().forEach(worldPlayer -> {
            VoyageAPI.evacuate(worldPlayer, worldName);
            // TODO send player evacuation message
        });
        /*
        Msg.sendResponsive(AudioResponse.SUCCESS, sender, instance.getLocalizationManager().getWithPrefix("world.kick.success"));
        Msg.sendResponsive(AudioResponse.ERROR, sender, instance.getLocalizationManager().getWithPrefix("world.kick.failure"));
         */
    }

    @Override
    public List<String> getAdditionalCompletions() {
        return VoyageAPI.allWorldFolderNames();
    }
}
