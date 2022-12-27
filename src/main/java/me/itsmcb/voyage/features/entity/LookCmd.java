package me.itsmcb.voyage.features.entity;

import me.itsmcb.vexelcore.bukkit.api.command.CustomCommand;
import me.itsmcb.vexelcore.bukkit.api.utils.LocationUtils;
import me.itsmcb.vexelcore.bukkit.api.utils.Msg;
import org.bukkit.entity.Entity;
import org.bukkit.entity.Player;

public class LookCmd extends CustomCommand {

    private Entity entity;

    public LookCmd(Entity entity) {
        super("look", "Have entity's head look at your current position","voyage.admin");
        this.entity = entity;
    }

    @Override
    public void executeAsPlayer(Player player, String[] args) {
        LocationUtils.entityLookAtPlayer(player, entity);
        Msg.send(player, "&3Entity now looking at you");
    }
}
