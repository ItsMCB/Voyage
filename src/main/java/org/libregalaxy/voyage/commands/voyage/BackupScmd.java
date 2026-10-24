package org.libregalaxy.voyage.commands.voyage;

import com.mojang.brigadier.arguments.StringArgumentType;
import com.mojang.brigadier.context.CommandContext;
import io.papermc.paper.command.brigadier.CommandSourceStack;
import me.itsmcb.vexelcore.bukkit.api.framework.TextUtils;
import me.itsmcb.vexelcore.bukkit.api.framework.VexelArg;
import me.itsmcb.vexelcore.bukkit.api.framework.VexelSenderSubCommand;
import net.kyori.adventure.text.minimessage.tag.resolver.Placeholder;
import org.bukkit.Bukkit;
import org.bukkit.command.CommandSender;
import org.jetbrains.annotations.NotNull;
import org.libregalaxy.voyage.VoyageContext;

import java.nio.file.Path;
import java.util.List;

public class BackupScmd extends VexelSenderSubCommand<VoyageContext> {

    private final VexelArg<String> LABEL_ARG;

    public BackupScmd(@NotNull VoyageContext context) {
        super(context, "backup", "Triggers a hot atomic snapshot of the Voyage SQLite database.", "voyage.admin");
        this.LABEL_ARG = VexelArg.builder("label", StringArgumentType.word(), String.class, "Optional custom backup label")
                .optional()
                .build();
    }

    @Override
    public @NotNull List<VexelArg<?>> getArguments() {
        return List.of(LABEL_ARG);
    }

    @Override
    public void execute(@NotNull CommandContext<CommandSourceStack> ctx, @NotNull CommandSender sender) {
        String label = optionalArg(ctx, LABEL_ARG);

        TextUtils.send(sender, "<color:#e98df5>ℹ</color> <yellow>Executing hot atomic snapshot of voyage.db...</yellow>");
        long start = System.currentTimeMillis();

        Bukkit.getScheduler().runTaskAsynchronously(context.getPlugin(), () -> {
            try {
                Path backupPath = context.getDatabaseService().performRollingBackup(label);
                long elapsed = System.currentTimeMillis() - start;

                Bukkit.getScheduler().runTask(context.getPlugin(), () -> {
                    TextUtils.send(sender, "<color:#e98df5>✅</color> <green>Database backup complete in <time>ms!</green> <gray>(File: <file>)</gray>",
                            Placeholder.unparsed("time", String.valueOf(elapsed)),
                            Placeholder.unparsed("file", backupPath.getFileName().toString()));
                });
            } catch (Exception e) {
                Bukkit.getScheduler().runTask(context.getPlugin(), () -> {
                    TextUtils.send(sender, "<color:#e98df5>❌</color> <red>Backup failed: <error></red>",
                            Placeholder.unparsed("error", e.getMessage() != null ? e.getMessage() : e.getClass().getSimpleName()));
                });
            }
        });
    }
}