package me.itsmcb.voyage.features.entity;

import me.itsmcb.vexelcore.bukkit.api.command.CustomCommand;
import me.itsmcb.vexelcore.bukkit.api.utils.Msg;
import org.bukkit.EntityEffect;
import org.bukkit.entity.Entity;
import org.bukkit.entity.Player;

public class TpHereCmd extends CustomCommand {

    private Entity entity;

    public TpHereCmd(Entity entity) {
        super("tphere", "Teleport entity to you","voyage.admin");
        this.entity = entity;
    }

    @Override
    public void executeAsPlayer(Player player, String[] args) {
        entity.teleport(player.getLocation());
        entity.playEffect(EntityEffect.ENTITY_POOF);
        Msg.send(player, "&3Entity has been teleported.");
    }
}
