package me.itsmcb.voyage.features.world;

import me.itsmcb.vexelcore.bukkit.api.command.CustomCommand;
import me.itsmcb.vexelcore.bukkit.api.experience.AudioResponse;
import me.itsmcb.vexelcore.bukkit.api.text.BukkitMsgBuilder;
import me.itsmcb.vexelcore.bukkit.api.utils.Msg;
import me.itsmcb.vexelcore.common.api.command.CMDHelper;
import me.itsmcb.voyage.Voyage;
import me.itsmcb.voyage.api.VoyageWorld;
import org.bukkit.World;
import org.bukkit.entity.Player;

public class BorderCmd extends CustomCommand {

    private Voyage instance;

    public BorderCmd(Voyage instance) {
        super("border", "Set world border","voyage.admin");
        this.instance = instance;
    }

    @Override
    public void executeAsPlayer(Player player, String[] args) {
        CMDHelper cmdHelper = new CMDHelper(args);
        if (!(cmdHelper.isDouble(0))) {
            new BukkitMsgBuilder("&cMust provide a number!").send(player);
            return;
        }
        VoyageWorld voyageWorld = new VoyageWorld(player.getWorld());
        World world = voyageWorld.getWorld();
        // TODO validate arg exists and is number
        world.getWorldBorder().setSize(Double.parseDouble(args[0]));
        Msg.sendResponsive(AudioResponse.SUCCESS, player, instance.getLocalizationManager().getWithPrefix("world.configure-border-success", args[0]));
    }
}
