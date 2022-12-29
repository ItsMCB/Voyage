package me.itsmcb.voyage.features.entity;

import me.itsmcb.vexelcore.bukkit.api.command.CustomCommand;
import me.itsmcb.vexelcore.bukkit.api.text.BukkitMsgBuilder;
import me.itsmcb.vexelcore.common.api.command.CMDHelper;
import me.itsmcb.voyage.Voyage;
import net.kyori.adventure.text.TextComponent;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Entity;
import org.bukkit.entity.Player;

public class EntityCmd extends CustomCommand {

    private Voyage instance;
    private EntityFeat feature;

    public EntityCmd(Voyage instance, EntityFeat feature) {
        super("entity", "Manage entities", "voyage.admin");
        this.instance = instance;
        this.feature = feature;
        registerSubCommand(new SelectCmd(instance, feature));
        registerSubCommand(new NearCmd(instance));
        registerStipulatedSubCommand(new LookCmd(null));
        registerStipulatedSubCommand(new WearHandCmd(null));
        registerStipulatedSubCommand(new TpToCmd(null));
        registerStipulatedSubCommand(new TpHereCmd(null));
        registerStipulatedSubCommand(new MountCmd(null));
        registerStipulatedSubCommand(new RemoveCmd(instance, null));
    }

    @Override
    public void executeAsPlayer(Player player, String[] args) {
        CMDHelper cmdHelper = new CMDHelper(args);
        if (cmdHelper.argNotExists(0)) {
            player.sendMessage(help());
            return;
        }
        // Validate selected entity
        Entity entity;
        if (!feature.validateSelected(player)) {
            new BukkitMsgBuilder("Entity not valid. Please reselect.").send(player);
            return;
        }
        entity = feature.getSelected(player);
        if (cmdHelper.isCalling("look")) {
            new LookCmd(entity).executeAsPlayer(player, args);
            return;
        }
        if (cmdHelper.isCalling("wearhand")) {
            new WearHandCmd(entity).executeAsPlayer(player, args);
            return;
        }
        if (cmdHelper.isCalling("tpto")) {
            new TpToCmd(entity).executeAsPlayer(player, args);
            return;
        }
        if (cmdHelper.isCalling("tphere")) {
            new TpHereCmd(entity).executeAsPlayer(player, args);
            return;
        }
        if (cmdHelper.isCalling("mount")) {
            new MountCmd(entity).executeAsPlayer(player,args);
            return;
        }
        if (cmdHelper.isCalling("remove")) {
            new RemoveCmd(instance,entity).executeAsPlayer(player,args);
            return;
        }
        player.sendMessage(help());
    }

    @Override
    public void executeAsConsole(CommandSender console, String[] args) {
        new BukkitMsgBuilder(instance.getLocalizationManager().getWithPrefix("error-player-only-command")).send(console);
    }

    @Override
    public TextComponent permissionError() {
        return new BukkitMsgBuilder(instance.getLocalizationManager().getWithPrefix("error-permission")).get();
    }

}
