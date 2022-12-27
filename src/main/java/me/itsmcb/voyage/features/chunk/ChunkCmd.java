package me.itsmcb.voyage.features.chunk;

import me.itsmcb.vexelcore.bukkit.api.command.CustomCommand;
import me.itsmcb.vexelcore.bukkit.api.text.BukkitMsgBuilder;
import me.itsmcb.voyage.Voyage;
import net.kyori.adventure.text.TextComponent;
import org.bukkit.command.CommandSender;

public class ChunkCmd extends CustomCommand {

    private Voyage instance;

    public ChunkCmd(Voyage instance) {
        super("chunk", "Manage chunks","voyage.admin");
        this.instance = instance;
        registerSubCommand(new OverviewCmd(instance));
        registerSubCommand(new EntitiesCmd(instance));
        registerSubCommand(new MaterialsCmd(instance));
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
