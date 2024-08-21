package me.itsmcb.voyage.features.world;

import me.itsmcb.vexelcore.bukkit.api.command.CustomCommand;
import me.itsmcb.vexelcore.bukkit.api.text.BukkitMsgBuilder;
import me.itsmcb.voyage.Voyage;
import me.itsmcb.voyage.api.VoyageAPI;
import net.kyori.adventure.text.event.ClickEvent;
import org.bukkit.entity.Player;

public class ListCmd extends CustomCommand {

    private Voyage instance;

    public ListCmd(Voyage instance) {
        super("list", "List of worlds","voyage.admin");
        this.instance = instance;
    }

    @Override
    public void executeAsPlayer(Player player, String[] args) {
        new BukkitMsgBuilder(instance.getLocalizationManager().get("bar", instance.getLocalizationManager().get("world.list.header-title"))).send(player);
        VoyageAPI.loadedWorlds().forEach(world -> {
            new BukkitMsgBuilder(instance.getLocalizationManager().get("world.list.entry-loaded", world.getName()))
                    .hover(instance.getLocalizationManager().get("world.list.click-to-teleport"))
                    .clickEvent(ClickEvent.Action.RUN_COMMAND, "/world teleport \"" + world.getName()+"\"")
                    .send(player);
        });
        VoyageAPI.unloadedWorlds().forEach(worldName -> {
            new BukkitMsgBuilder(instance.getLocalizationManager().get("world.list.entry-unloaded", worldName))
                    .hover(instance.getLocalizationManager().get("world.list.click-to-load"))
                    .clickEvent(ClickEvent.Action.RUN_COMMAND, "/world load \"" + worldName+"\"")
                    .send(player);
        });
    }
}
