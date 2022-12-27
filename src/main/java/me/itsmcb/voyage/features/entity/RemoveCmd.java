package me.itsmcb.voyage.features.entity;

import me.itsmcb.vexelcore.bukkit.api.command.CustomCommand;
import me.itsmcb.vexelcore.bukkit.api.utils.Msg;
import me.itsmcb.voyage.Voyage;
import org.bukkit.EntityEffect;
import org.bukkit.Sound;
import org.bukkit.entity.Entity;
import org.bukkit.entity.Player;

public class RemoveCmd extends CustomCommand {

    private Voyage instance;
    private Entity entity;

    public RemoveCmd(Voyage instance, Entity entity) {
        super("remove", "Delete entity from world","voyage.admin");
        this.instance = instance;
        this.entity = entity;
    }

    @Override
    public void executeAsPlayer(Player player, String[] args) {
        entity.playEffect(EntityEffect.ENTITY_POOF);
        entity.remove();
        player.playSound(player.getLocation(), Sound.ENTITY_ENDER_DRAGON_HURT, (float) 1, 1);
        Msg.send(player, "&3Killed &b" + entity.getType());
    }
}
