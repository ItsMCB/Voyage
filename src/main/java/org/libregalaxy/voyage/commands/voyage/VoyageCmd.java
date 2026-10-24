package org.libregalaxy.voyage.commands.voyage;

import me.itsmcb.vexelcore.bukkit.api.framework.VexelCommandNode;
import me.itsmcb.vexelcore.bukkit.api.framework.VexelRootCommand;
import org.jetbrains.annotations.NotNull;
import org.libregalaxy.voyage.VoyageContext;

import java.util.List;

public class VoyageCmd extends VexelRootCommand<VoyageContext> {

    public VoyageCmd(@NotNull VoyageContext context) {
        super(context, "voyage", "Voyage administrative commands", "voyage.admin");
    }

    @Override
    public @NotNull List<VexelCommandNode> getChildren() {
        return List.of(
                new ReloadScmd(context),
                new BackupScmd(context),
                new ResetDbScmd(context)
        );
    }
}