package me.itsmcb.voyage.features.voyage;

import me.itsmcb.vexelcore.bukkit.api.experience.AudioResponse;
import me.itsmcb.vexelcore.bukkit.api.text.BukkitMsgBuilder;
import me.itsmcb.vexelcore.bukkit.api.utils.Msg;
import me.itsmcb.voyage.Voyage;
import org.bukkit.command.Command;
import org.bukkit.command.CommandSender;
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
            Msg.sendResponsive(AudioResponse.ERROR, sender, instance.getLocalizationManager().getWithPrefix("error-permission"));
            return true;
        }
        if (!(sender instanceof Player)) {
            new BukkitMsgBuilder(instance.getLocalizationManager().getWithPrefix("error-player-only-command")).send(sender);
            return true;
        }

        Player player = (Player) sender;

        /*
        Msg.send(sender,
                instance.getLocalizationManager().get("bar", instance.getLocalizationManager().get("voyage.header-title")),
                instance.getLocalizationManager().get("result", "/world", instance.getLocalizationManager().get("world.help.about")),
                instance.getLocalizationManager().get("result", "/entity", instance.getLocalizationManager().get("entity.help.about")),
                instance.getLocalizationManager().get("result", "/chunk", instance.getLocalizationManager().get("chunk.help.about"))
        );

         */

        return false;
    }

}
