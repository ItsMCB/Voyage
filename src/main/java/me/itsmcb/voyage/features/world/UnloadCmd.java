package me.itsmcb.voyage.features.world;

import me.itsmcb.vexelcore.bukkit.api.command.CustomCommand;
import me.itsmcb.vexelcore.bukkit.api.text.BukkitMsgBuilder;
import me.itsmcb.voyage.Voyage;
import me.itsmcb.voyage.api.VoyageAPI;
import me.itsmcb.voyage.api.VoyageWorld;
import org.bukkit.entity.Player;
import org.bukkit.generator.WorldInfo;

import java.util.List;

public class UnloadCmd extends CustomCommand {

    private Voyage instance;

    public UnloadCmd(Voyage instance) {
        super("unload", "Unload world so players can't join","voyage.admin");
        this.instance = instance;
    }

    @Override
    public void executeAsPlayer(Player player, String[] args) {
        if (args.length == 0) {
            player.sendMessage(help());
            return;
        }
        VoyageWorld voyageWorld = new VoyageWorld(args[0]);
        if (!voyageWorld.unload()) {
            new BukkitMsgBuilder("&cFailed").send(player);
            return;
        }
        // TODO localize
        new BukkitMsgBuilder("&aSuccess").send(player);
    }

    @Override
    public List<String> getAdditionalCompletions() {
        return VoyageAPI.loadedWorlds().stream().map(WorldInfo::getName).toList();
    }
}
