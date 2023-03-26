package me.itsmcb.voyage.features.voyage;

import me.itsmcb.vexelcore.bukkit.api.command.CustomCommand;
import me.itsmcb.vexelcore.bukkit.api.text.BukkitMsgBuilder;
import me.itsmcb.vexelcore.common.api.command.CMDHelper;
import me.itsmcb.voyage.Voyage;
import me.itsmcb.voyage.features.chunk.ChunkCmd;
import me.itsmcb.voyage.features.entity.EntityCmd;
import me.itsmcb.voyage.features.entity.EntityFeat;
import me.itsmcb.voyage.features.world.WorldCmd;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Player;

import java.util.List;

public class VoyageCmd extends CustomCommand {

    private Voyage instance;

    public VoyageCmd(Voyage instance) {
        super("voyage", "All of Voyage", "voyage.admin");
        this.instance = instance;
        registerSubCommand(new ChunkCmd(instance));
        registerSubCommand(new EntityCmd(instance, new EntityFeat(instance)));
        registerSubCommand(new WorldCmd(instance));
    }

    @Override
    public void executeAsPlayer(Player player, String[] args) {
        CMDHelper cmdHelper = new CMDHelper(args);
        if (cmdHelper.isCalling("reload")) {
            instance.getMainConfig().reload();
            // todo localize reload message
            new BukkitMsgBuilder("&aReloaded config!").send(player);
            return;
        }
        player.sendMessage(help());
    }

    @Override
    public List<String> getAdditionalCompletions(CommandSender sender) {
        return List.of("reload");
    }
}
