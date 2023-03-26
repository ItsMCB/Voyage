package me.itsmcb.voyage.features.entity;

import me.itsmcb.vexelcore.bukkit.api.command.CustomCommand;
import me.itsmcb.vexelcore.bukkit.api.text.BukkitMsgBuilder;
import me.itsmcb.vexelcore.common.api.command.CMDHelper;
import me.itsmcb.voyage.Voyage;
import net.kyori.adventure.text.event.ClickEvent;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Entity;
import org.bukkit.entity.Player;

import java.util.List;

public class NearCmd extends CustomCommand {

    private Voyage instance;

    public NearCmd(Voyage instance) {
        super("near", "View entities near you","voyage.admin");
        super.addParameter("[int]","Radius to search for entities");
        this.instance = instance;
    }

    @Override
    public void executeAsPlayer(Player player, String[] args) {
        CMDHelper cmdHelper = new CMDHelper(args);
        if (!cmdHelper.isInt(0)) {
            // TODO localize
            new BukkitMsgBuilder("&cInvalid number!").send(player);
            return;
        }
        double d = Double.parseDouble(args[0]);
        List<Entity> near = player.getNearbyEntities(d, d, d);
        for (Entity entity : near) {
            new BukkitMsgBuilder("&7- &a"+entity.getName() + "&7 | &3UUID: &b"+entity.getUniqueId())
                    .hover("Click to copy")
                    .clickEvent(ClickEvent.Action.COPY_TO_CLIPBOARD, entity.getUniqueId()+"")
                    .send(player);
        }
    }

    @Override
    public List<String> getCompletions(CommandSender sender) {
        return List.of("10","20","30","40","50","60");
    }
}
