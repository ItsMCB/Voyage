package me.itsmcb.voyage.features.voyage;

import me.itsmcb.vexelcore.bukkit.api.experience.AudioResponse;
import me.itsmcb.vexelcore.bukkit.api.utils.LocationUtils;
import me.itsmcb.vexelcore.bukkit.api.utils.Msg;
import me.itsmcb.vexelcore.common.api.command.CMDHelper;
import me.itsmcb.voyage.FormatUtils;
import me.itsmcb.voyage.Voyage;
import org.bukkit.command.Command;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Entity;
import org.bukkit.entity.Player;
import org.jetbrains.annotations.NotNull;

public class VoyageCMD extends Command {

    private Voyage instance;

    public VoyageCMD(Voyage instance) {
        super("voyage");
        this.instance = instance;
    }

    @Override
    public boolean execute(@NotNull CommandSender sender, @NotNull String commandLabel, @NotNull String[] args) {
        if (!sender.hasPermission("voyage.admin")) {
            Msg.sendResponsive(AudioResponse.ERROR, sender, "&cOops! You lack permission to do this. To use, give yourself the following permission: voyage.admin");
            return true;
        }
        if (!(sender instanceof Player)) {
            Msg.send(sender, "&cOops! Only players can execute this command.");
            return true;
        }
        Player player = (Player) sender;
        CMDHelper cmdHelper = new CMDHelper(args);
        if (cmdHelper.argExists(0)) {
            if (cmdHelper.isCalling("select")) {
                Entity targetEntity = player.getTargetEntity(10);
                if (!(targetEntity instanceof Player)) {
                    // TODO sent component with entity information upon hover
                    Msg.send(sender,"&3Found entity &b" + targetEntity.getType().name() + "&3 at &b" + LocationUtils.getAsString(targetEntity.getLocation(), LocationUtils.LocationStringStyle.SPACE));
                    instance.selectedEntities.put(player.getUniqueId(), targetEntity.getUniqueId());
                }
                return true;
            }
        }
        Msg.send(sender,
                "&7========== &6 Entity Command Help &7 ==========",
                FormatUtils.format("/entity select", "Selects entity that crosshair is on."),
                FormatUtils.format("/entity tphere", "Teleports selected entity to current location."),
                FormatUtils.format("/entity ride", "Rides entity."),
                FormatUtils.format("/entity remove", "Deletes entity from world.")
        );
        return false;
    }

}
