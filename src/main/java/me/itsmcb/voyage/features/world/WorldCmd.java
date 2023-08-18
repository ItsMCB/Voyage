package me.itsmcb.voyage.features.world;

import me.itsmcb.vexelcore.bukkit.api.command.CustomCommand;
import me.itsmcb.voyage.Voyage;

public class WorldCmd extends CustomCommand {

    private Voyage instance;

    public WorldCmd(Voyage instance) {
        super("world","Manage your worlds","voyage.admin");
        this.instance = instance;
        registerSubCommand(new CreateCmd(instance));
        registerSubCommand(new ListCmd(instance));
        registerSubCommand(new LoadCmd(instance));
        registerSubCommand(new UnloadCmd(instance));
        registerSubCommand(new CopySCmd(instance));
        registerSubCommand(new DeleteCmd(instance));
        registerSubCommand(new TeleportCmd(instance));
        registerSubCommand(new InfoCmd(instance));
        registerSubCommand(new BorderCmd(instance));
        registerSubCommand(new EvacuateCmd(instance));
        registerSubCommand(new GeneratorsCmd(instance));
    }

}
