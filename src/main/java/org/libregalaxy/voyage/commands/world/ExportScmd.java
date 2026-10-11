package org.libregalaxy.voyage.commands.world;

import com.mojang.brigadier.context.CommandContext;
import io.papermc.paper.command.brigadier.CommandSourceStack;
import io.papermc.paper.command.brigadier.argument.ArgumentTypes;
import me.itsmcb.vexelcore.bukkit.api.framework.TextUtils;
import me.itsmcb.vexelcore.bukkit.api.framework.VexelArg;
import me.itsmcb.vexelcore.bukkit.api.framework.VexelPlayerSubCommand;
import net.kyori.adventure.text.minimessage.tag.resolver.Placeholder;
import org.bukkit.Bukkit;
import org.bukkit.NamespacedKey;
import org.bukkit.entity.Player;
import org.libregalaxy.voyage.VoyageContext;
import org.jetbrains.annotations.NotNull;

import java.nio.file.Path;
import java.util.List;

public class ExportScmd extends VexelPlayerSubCommand<VoyageContext> {

    private final VexelArg<NamespacedKey> WORLD_ARG;

    public ExportScmd(@NotNull VoyageContext context) {
        super(context, "export", "Exports a world to a zipped archive, including your Voyage player data.", "voyage.export");
        this.WORLD_ARG = VexelArg.builder("world", ArgumentTypes.namespacedKey(), NamespacedKey.class, "Dimension to export")
                .suggests(() -> context.getWorldManager().getTrackedWorldNames())
                .suggestionMode(VexelArg.SuggestionMode.NAMESPACED_KEY)
                .build();
    }

    @Override public @NotNull List<VexelArg<?>> getArguments() { return List.of(WORLD_ARG); }

    @Override
    public void execute(@NotNull CommandContext<CommandSourceStack> ctx, @NotNull Player player) {
        NamespacedKey key = requireArg(ctx, WORLD_ARG);

        TextUtils.send(player, "<color:#e98df5>ℹ</color> <yellow>Starting export of <white><world></white>...</yellow>", Placeholder.unparsed("world", key.asString()));

        Path targetZip = Bukkit.getWorldContainer().toPath().resolve("exports").resolve(key.asString().replace(":", "_") + ".zip");

        context.getWorldManager().exportWorld(key, targetZip, player.getUniqueId())
                .thenRun(() -> {
                    Bukkit.getScheduler().runTask(context.getPlugin(), () -> {
                        TextUtils.send(player, "<color:#e98df5>ℹ</color> <green>Successfully exported <white><world></white> to exports/ folder.</green>", Placeholder.unparsed("world", key.asString()));
                        context.getWebhookService().notify("WORLD_EXPORT", player, "Exported " + key.asString());
                    });
                })
                .exceptionally(ex -> {
                    Bukkit.getScheduler().runTask(context.getPlugin(), () -> {
                        Throwable cause = ex instanceof java.util.concurrent.CompletionException ? ex.getCause() : ex;
                        TextUtils.send(player, "<color:#e98df5>ℹ</color> <red>Failed to export world: <error></red>", Placeholder.unparsed("error", cause.getMessage()));
                    });
                    return null;
                });
    }
}