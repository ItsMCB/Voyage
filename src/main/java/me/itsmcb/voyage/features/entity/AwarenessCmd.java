package me.itsmcb.voyage.features.entity;

import me.itsmcb.vexelcore.bukkit.api.command.CustomCommand;
import me.itsmcb.vexelcore.bukkit.api.text.BukkitMsgBuilder;
import me.itsmcb.vexelcore.bukkit.api.utils.Msg;
import me.itsmcb.vexelcore.common.api.command.CMDHelper;
import org.bukkit.entity.Entity;
import org.bukkit.entity.Mob;
import org.bukkit.entity.Player;

public class AwarenessCmd extends CustomCommand {

    private Entity entity;

    public AwarenessCmd(Entity entity) {
        super("awareness", "Set awareness of entity","voyage.admin");
        this.entity = entity;
    }

    @Override
    public void executeAsPlayer(Player player, String[] args) {
        CMDHelper cmdHelper = new CMDHelper(args);
        if (entity.getType().isAlive()) {
            Mob mob = (Mob) entity;
            if (mob == null) {
                new BukkitMsgBuilder("Entity isn't a mob and thus can't be aware.").send(player);
                return;
            }
            if (args.length == 0) {
                new BukkitMsgBuilder("&7Is Aware: &a"+ mob.isAware()).send(player);
                new BukkitMsgBuilder("&7To edit awareness, add argument: true OR false").send(player);
                return;
            }
            mob.setAware(cmdHelper.isCalling("true"));
            Msg.send(player, "&3Set mob awareness to &b" + args[0]);
        }
    }
}
