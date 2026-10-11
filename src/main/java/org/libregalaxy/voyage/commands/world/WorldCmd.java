package org.libregalaxy.voyage.commands.world;

import me.itsmcb.vexelcore.bukkit.api.framework.VexelCommandNode;
import me.itsmcb.vexelcore.bukkit.api.framework.VexelRootCommand;
import org.libregalaxy.voyage.VoyageContext;
import org.jetbrains.annotations.NotNull;

import java.util.List;

public class WorldCmd extends VexelRootCommand<VoyageContext> {

    public WorldCmd(@NotNull VoyageContext context) {
        super(context, "world", "World management router", "voyage.world");
    }

    @Override
    public @NotNull List<VexelCommandNode> getChildren() {
        return List.of(
                new BorderScmd(context),
                new CloneScmd(context),
                new ManageScmd(context),
                new CreateScmd(context),
                new ProfileLinkScmd(context),
                new DeleteScmd(context),
                new EvacuateScmd(context),
                new ExportScmd(context),
                new InfoScmd(context),
                new PortalLinkScmd(context),
                new ListScmd(context),
                new LoadScmd(context),
                new TeleportScmd(context),
                new UnloadScmd(context),
                new ImportScmd(context)
        );
    }
}