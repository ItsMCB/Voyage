package me.itsmcb.voyage.features.entity;

import me.itsmcb.vexelcore.bukkit.api.command.CustomCommand;
import me.itsmcb.vexelcore.bukkit.api.utils.Msg;
import org.bukkit.entity.Entity;
import org.bukkit.entity.Player;

public class MountCmd extends CustomCommand {

    private Entity entity;

    public MountCmd(Entity entity) {
        super("mount", "Ride an entity","voyage.admin");
        this.entity = entity;
    }

    @Override
    public void executeAsPlayer(Player player, String[] args) {
        entity.addPassenger(player);
        Msg.send(player, "&3Now riding &b" + entity.getType());
    }
}
