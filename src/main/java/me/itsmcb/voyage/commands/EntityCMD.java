package me.itsmcb.voyage.commands;

import me.itsmcb.vexelcore.bukkit.api.experience.AudioResponse;
import me.itsmcb.vexelcore.bukkit.api.utils.LocationUtils;
import me.itsmcb.vexelcore.bukkit.api.utils.Msg;
import me.itsmcb.vexelcore.common.api.command.CMDHelper;
import me.itsmcb.voyage.FormatUtils;
import me.itsmcb.voyage.Voyage;
import org.bukkit.Bukkit;
import org.bukkit.EntityEffect;
import org.bukkit.Sound;
import org.bukkit.command.Command;
import org.bukkit.command.CommandExecutor;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Entity;
import org.bukkit.entity.Player;
import org.jetbrains.annotations.NotNull;

public class EntityCMD implements CommandExecutor {

    private Voyage instance;

    public EntityCMD(Voyage instance) {
        this.instance = instance;
    }

    @Override
    public boolean onCommand(@NotNull CommandSender sender, @NotNull Command command, @NotNull String label, @NotNull String[] args) {
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
            if (cmdHelper.isCalling("tphere")) {
                if (hasSelectedEntity(player)) {
                    Entity selectedEntity = Bukkit.getEntity(instance.selectedEntities.get(player.getUniqueId()));
                    selectedEntity.teleport(player.getLocation());
                    Msg.send(sender, "&3Entity has been teleported.");
                }
                return true;
            }
            if (cmdHelper.isCalling("ride")) {
                if (hasSelectedEntity(player)) {
                    Entity selectedEntity = Bukkit.getEntity(instance.selectedEntities.get(player.getUniqueId()));
                    selectedEntity.addPassenger(player);
                    Msg.send(sender, "&3Now riding &b" + selectedEntity.getType());
                }
                return true;
            }
            if (cmdHelper.isCalling("remove")) {
                if (hasSelectedEntity(player)) {
                    Entity selectedEntity = Bukkit.getEntity(instance.selectedEntities.get(player.getUniqueId()));
                    selectedEntity.playEffect(EntityEffect.ENTITY_POOF);
                    selectedEntity.remove();
                    player.playSound(player.getLocation(), Sound.ENTITY_ENDER_DRAGON_HURT, (float) 1, 1);
                    Msg.send(sender, "&3Killed &b" + selectedEntity.getType());
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

    private boolean hasSelectedEntity(Player player) {
        if (instance.selectedEntities.containsKey(player.getUniqueId())) {
            Entity selectedEntity = Bukkit.getEntity(instance.selectedEntities.get(player.getUniqueId()));
            if (selectedEntity == null) {
                Msg.send(player, "&cSelected entity can't be found. Please reselect.");
                return false;
            }
            return true;
        }
        return false;
    }
}
