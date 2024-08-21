package me.itsmcb.voyage.features.world;

import me.itsmcb.vexelcore.bukkit.api.command.CustomCommand;
import me.itsmcb.vexelcore.bukkit.api.experience.AudioResponse;
import me.itsmcb.vexelcore.bukkit.api.utils.Msg;
import me.itsmcb.voyage.Voyage;
import me.itsmcb.voyage.api.VoyageAPI;
import me.itsmcb.voyage.api.VoyageWorld;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Player;

import java.util.List;

public class LoadCmd extends CustomCommand {

    private Voyage instance;

    public LoadCmd(Voyage instance) {
        super("load", "Load world so that players can join it","voyage.admin");
        addParameter("[world]","The name of the world to teleport to.");
        this.instance = instance;
    }

    @Override
    public void executeAsPlayer(Player player, String[] args) {
        if (args.length == 0) {
            player.sendMessage(help());
            return;
        }
        VoyageWorld voyageWorld = new VoyageWorld(args[0]);
        // TODO check if world file exists
        //  new BukkitMsgBuilder(instance.getLocalizationManager().getWithPrefix("world.does-not-exist")).send(player);
        if (!voyageWorld.load()) {
            Msg.sendResponsive(AudioResponse.ERROR, player, instance.getLocalizationManager().getWithPrefix("world.load.failure"));
            return;
        }
        player.teleport(voyageWorld.getWorld().getSpawnLocation());
        Msg.sendResponsive(AudioResponse.SUCCESS, player, instance.getLocalizationManager().getWithPrefix("world.load.success"));
    }

    @Override
    public List<String> getAdditionalCompletions(CommandSender sender) {
        return VoyageAPI.unloadedWorlds().stream().map(s -> "\""+s+"\"").toList();
    }
}
