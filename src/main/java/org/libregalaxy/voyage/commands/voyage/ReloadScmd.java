package org.libregalaxy.voyage.commands.voyage;

import com.mojang.brigadier.context.CommandContext;
import io.papermc.paper.command.brigadier.CommandSourceStack;
import me.itsmcb.vexelcore.bukkit.api.framework.TextUtils;
import me.itsmcb.vexelcore.bukkit.api.framework.VexelArg;
import me.itsmcb.vexelcore.bukkit.api.framework.VexelSenderSubCommand;
import net.kyori.adventure.text.minimessage.tag.resolver.Placeholder;
import org.bukkit.command.CommandSender;
import org.libregalaxy.voyage.VoyageContext;
import org.jetbrains.annotations.NotNull;

import java.util.Collections;
import java.util.List;
import java.util.logging.Level;

/**
 * Reload Voyage plugin.
 */
public class ReloadScmd extends VexelSenderSubCommand<VoyageContext> {

    public ReloadScmd(@NotNull VoyageContext context) {
        super(context, "reload", "Reloads config and dimensions safely without a restart.", "voyage.admin");
    }

    @Override public @NotNull List<VexelArg<?>> getArguments() { return Collections.emptyList(); }

    @Override
    public void execute(@NotNull CommandContext<CommandSourceStack> ctx, @NotNull CommandSender sender) {
        TextUtils.send(sender, "<color:#e98df5>ℹ</color> <yellow>Saving states and reloading Voyage configurations...</yellow>");

        int failures = 0;

        try {
            context.getPlugin().reloadConfig();
        } catch (Exception e) {
            failures++;
            context.getPlugin().getLogger().log(Level.SEVERE, "Failed to reload config", e);
            TextUtils.send(sender, "<color:#e98df5>❌</color> <red>Failed to reload config: <error></red>",
                    Placeholder.unparsed("error", e.getMessage() != null ? e.getMessage() : e.getClass().getSimpleName()));
        }

        try {
            context.getPlayerDataManager().reload();
        } catch (Exception e) {
            failures++;
            context.getPlugin().getLogger().log(Level.SEVERE, "Failed to reload Player Data Manager", e);
            TextUtils.send(sender, "<color:#e98df5>❌</color> <red>Failed to reload player data: <error></red>",
                    Placeholder.unparsed("error", e.getMessage() != null ? e.getMessage() : e.getClass().getSimpleName()));
        }

        try {
            context.getWorldManager().reload();
        } catch (Exception e) {
            failures++;
            context.getPlugin().getLogger().log(Level.SEVERE, "Failed to reload World Manager", e);
            TextUtils.send(sender, "<color:#e98df5>❌</color> <red>Failed to reload worlds: <error></red>",
                    Placeholder.unparsed("error", e.getMessage() != null ? e.getMessage() : e.getClass().getSimpleName()));
        }

        if (failures == 0) {
            TextUtils.send(sender, "<color:#e98df5>✅</color> <green>Voyage reloaded successfully!</green>");
        } else {
            TextUtils.send(sender, "<color:#e98df5>⚠️</color> <red>Voyage reloaded with <count> error(s). Check console for details.</red>",
                    Placeholder.unparsed("count", String.valueOf(failures)));
        }
    }
}