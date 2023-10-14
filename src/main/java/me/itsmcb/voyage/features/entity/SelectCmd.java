package me.itsmcb.voyage.features.entity;

import me.itsmcb.vexelcore.bukkit.api.command.CustomCommand;
import me.itsmcb.vexelcore.bukkit.api.text.BukkitMsgBuilder;
import me.itsmcb.vexelcore.common.api.command.CMDHelper;
import me.itsmcb.voyage.Voyage;
import org.bukkit.Bukkit;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Entity;
import org.bukkit.entity.Player;

import java.util.List;
import java.util.UUID;

public class SelectCmd extends CustomCommand {

    private Voyage instance;
    private EntityFeat feature;

    public SelectCmd(Voyage instance, EntityFeat feature) {
        super("select", "Select entity to manage it","voyage.admin");
        this.instance = instance;
        this.feature = feature;
    }

    @Override
    public void executeAsPlayer(Player player, String[] args) {
        CMDHelper cmdHelper = new CMDHelper(args);
        if (cmdHelper.isCalling("target")) {
            Entity entity = player.getTargetEntity(10);
            if (entity == null) {
                // TODO localize
                new BukkitMsgBuilder("&cInvalid target!").send(player);
                return;
            }
            select(entity,player);
            return;
        }
        if (cmdHelper.isCalling("uuid")) {
            Entity entity = Bukkit.getEntity(UUID.fromString(args[1]));
            if (entity == null) {
                // TODO localize
                new BukkitMsgBuilder("&cInvalid target!").send(player);
                return;
            }
            select(entity,player);
            return;
        }
        help(player);
    }

    private void select(Entity entity, Player player) {
        feature.select(player, entity);
        new BukkitMsgBuilder("&aSelected "+entity.getType().getKey().getKey()).send(player);
    }

    @Override
    public List<String> getCompletions(CommandSender sender) {
        return List.of("target","uuid");
    }
}
