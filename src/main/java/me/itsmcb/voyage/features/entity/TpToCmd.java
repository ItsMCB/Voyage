package me.itsmcb.voyage.features.entity;

import me.itsmcb.vexelcore.bukkit.api.command.CustomCommand;
import me.itsmcb.vexelcore.bukkit.api.utils.Msg;
import org.bukkit.EntityEffect;
import org.bukkit.entity.Entity;
import org.bukkit.entity.Player;

public class TpToCmd extends CustomCommand {

    private Entity entity;

    public TpToCmd(Entity entity) {
        super("tpto", "Teleport to an entity","voyage.admin");
        this.entity = entity;
    }

    @Override
    public void executeAsPlayer(Player player, String[] args) {
        player.teleport(entity.getLocation());
        player.playEffect(EntityEffect.ENTITY_POOF);
        Msg.send(player, "&3Teleported to entity.");
    }
}
