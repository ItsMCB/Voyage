package org.libregalaxy.voyage.commands.voyage;

import com.mojang.brigadier.arguments.StringArgumentType;
import com.mojang.brigadier.context.CommandContext;
import io.papermc.paper.command.brigadier.CommandSourceStack;
import me.itsmcb.vexelcore.bukkit.api.framework.TextUtils;
import me.itsmcb.vexelcore.bukkit.api.framework.VexelArg;
import me.itsmcb.vexelcore.bukkit.api.framework.VexelSenderSubCommand;
import org.bukkit.command.CommandSender;
import org.jetbrains.annotations.NotNull;
import org.libregalaxy.voyage.VoyageContext;

import java.util.List;

public class ResetDbScmd extends VexelSenderSubCommand<VoyageContext> {

    private final VexelArg<String> CONFIRM_ARG;

    public ResetDbScmd(@NotNull VoyageContext context) {
        super(context, "db-reset", "Wipes the Voyage database and recreates schemas (Development tool).", "voyage.admin");
        this.CONFIRM_ARG = VexelArg.builder("confirm", StringArgumentType.word(), String.class, "Confirm database wipe")
                .optional()
                .suggests(List.of("--confirm"))
                .build();
    }

    @Override
    public @NotNull List<VexelArg<?>> getArguments() {
        return List.of(CONFIRM_ARG);
    }

    @Override
    public void execute(@NotNull CommandContext<CommandSourceStack> ctx, @NotNull CommandSender sender) {
        String confirm = optionalArg(ctx, CONFIRM_ARG);

        if (!"--confirm".equals(confirm)) {
            TextUtils.send(sender, "<color:#e98df5>ℹ</color> <red>This will purge all world registry data and player profiles in SQLite!</red>");
            TextUtils.send(sender, "<gray>Run <white>/voyage db-reset --confirm</white> to proceed.</gray>");
            return;
        }

        TextUtils.send(sender, "<color:#e98df5>ℹ</color> <yellow>Wiping voyage.db and rebuilding schemas...</yellow>");

        context.getDatabaseService().resetDatabase().thenRun(() -> {
            context.getWorldManager().reload();
            TextUtils.send(sender, "<color:#e98df5>✅</color> <green>Database reset and schema rebuild successful!</green>");
        }).exceptionally(ex -> {
            TextUtils.send(sender, "<color:#e98df5>❌</color> <red>Database reset failed: " + ex.getMessage() + "</red>");
            return null;
        });
    }
}