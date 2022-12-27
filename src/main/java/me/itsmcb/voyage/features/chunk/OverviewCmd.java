package me.itsmcb.voyage.features.chunk;

import me.itsmcb.vexelcore.bukkit.api.command.CustomCommand;
import me.itsmcb.vexelcore.bukkit.api.text.BukkitMsgBuilder;
import me.itsmcb.vexelcore.bukkit.api.utils.Msg;
import me.itsmcb.voyage.Voyage;
import net.kyori.adventure.text.TextComponent;
import org.bukkit.Chunk;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Player;

public class OverviewCmd extends CustomCommand {

    private Voyage instance;

    public OverviewCmd(Voyage instance) {
        super("overview", "Chunk overview","voyage.admin");
        this.instance = instance;
    }

    @Override
    public void executeAsPlayer(Player player, String[] args) {
        Chunk chunk = player.getChunk();
        Msg.send(player,
                instance.getLocalizationManager().get("bar", instance.getLocalizationManager().get("chunk.header-title")),
                instance.getLocalizationManager().get("result",  instance.getLocalizationManager().get("chunk.general.location"), chunk.getWorld().getName() + " @ " + chunk.getX()+";" + chunk.getZ()+""),
                instance.getLocalizationManager().get("result", instance.getLocalizationManager().get("chunk.general.entities"), Integer.toString(chunk.getEntities().length)),
                instance.getLocalizationManager().get("result", instance.getLocalizationManager().get("chunk.general.slime"), Boolean.toString(chunk.isSlimeChunk()))
        );
    }

    @Override
    public void executeAsConsole(CommandSender console, String[] args) {
        new BukkitMsgBuilder(instance.getLocalizationManager().getWithPrefix("error-player-only-command")).send(console);
    }

    @Override
    public TextComponent permissionError() {
        return new BukkitMsgBuilder(instance.getLocalizationManager().getWithPrefix("error-permission")).get();
    }
}
