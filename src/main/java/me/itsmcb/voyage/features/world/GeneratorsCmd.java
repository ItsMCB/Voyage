package me.itsmcb.voyage.features.world;

import me.itsmcb.vexelcore.bukkit.api.command.CustomCommand;
import me.itsmcb.vexelcore.bukkit.api.text.BukkitMsgBuilder;
import me.itsmcb.voyage.Voyage;
import me.itsmcb.voyage.api.VoyageAPI;
import org.bukkit.entity.Player;

public class GeneratorsCmd extends CustomCommand {

    private Voyage instance;

    public GeneratorsCmd(Voyage instance) {
        super("generators", "View world generators", "voyage.admin");
        this.instance = instance;
    }

    @Override
    public void executeAsPlayer(Player player, String[] args) {
        new BukkitMsgBuilder( instance.getLocalizationManager().get("world.generators.header")).send(player);
        VoyageAPI.getGenerators().forEach(generator -> {
            new BukkitMsgBuilder(instance.getLocalizationManager().get("world.generators.entry", generator.getName(), generator.getAuthorsFormatted())).send(player);
        });
    }
}
