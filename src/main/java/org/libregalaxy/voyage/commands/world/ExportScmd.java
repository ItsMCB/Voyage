package org.libregalaxy.voyage.commands.world;

import com.mojang.brigadier.arguments.StringArgumentType;
import com.mojang.brigadier.context.CommandContext;
import io.papermc.paper.command.brigadier.CommandSourceStack;
import io.papermc.paper.command.brigadier.argument.ArgumentTypes;
import me.itsmcb.vexelcore.bukkit.api.framework.TextUtils;
import me.itsmcb.vexelcore.bukkit.api.framework.VexelArg;
import me.itsmcb.vexelcore.bukkit.api.framework.VexelSenderSubCommand;
import net.kyori.adventure.text.minimessage.tag.resolver.Placeholder;
import org.bukkit.Bukkit;
import org.bukkit.NamespacedKey;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Player;
import org.jetbrains.annotations.NotNull;
import org.libregalaxy.voyage.VoyageContext;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.UUID;

public class ExportScmd extends VexelSenderSubCommand<VoyageContext> {

    private final VexelArg<NamespacedKey> WORLD_ARG;
    private final VexelArg<String> DATA_OPTION_ARG;

    public ExportScmd(@NotNull VoyageContext context) {
        super(context, "export", "Exports a world to a zipped archive with configuration and player data.", "voyage.export");
        this.WORLD_ARG = VexelArg.builder("world", ArgumentTypes.namespacedKey(), NamespacedKey.class, "Dimension to export")
                .suggests(() -> context.getWorldManager().getTrackedWorldNames())
                .suggestionMode(VexelArg.SuggestionMode.NAMESPACED_KEY)
                .build();

        this.DATA_OPTION_ARG = VexelArg.builder("data_option", StringArgumentType.word(), String.class, "'--with-data' or '--no-data'")
                .optional()
                .suggests(List.of("--with-data", "--no-data"))
                .build();
    }

    @Override public @NotNull List<VexelArg<?>> getArguments() { return List.of(WORLD_ARG, DATA_OPTION_ARG); }

    @Override
    public void execute(@NotNull CommandContext<CommandSourceStack> ctx, @NotNull CommandSender sender) {
        NamespacedKey key = requireArg(ctx, WORLD_ARG);
        String dataOption = optionalArg(ctx, DATA_OPTION_ARG);
        boolean includePlayerData = !"--no-data".equalsIgnoreCase(dataOption);

        TextUtils.send(sender, "<color:#e98df5>ℹ</color> <yellow>Starting export of <white><world></white> (<mode>)...</yellow>",
                Placeholder.unparsed("world", key.asString()),
                Placeholder.unparsed("mode", includePlayerData ? "with player data" : "terrain & config only"));

        Path exportDir = context.getPlugin().getDataFolder().toPath().resolve("exports");
        try {
            Files.createDirectories(exportDir);
        } catch (IOException e) {
            TextUtils.send(sender, "<color:#e98df5>ℹ</color> <red>Failed to prepare exports directory: <error></red>",
                    Placeholder.unparsed("error", e.getMessage() != null ? e.getMessage() : e.getClass().getSimpleName()));
            return;
        }
        Path targetZip = exportDir.resolve(key.asString().replace(":", "_") + ".zip");

        UUID primaryUuid = (sender instanceof Player p) ? p.getUniqueId() : null;

        context.getWorldManager().exportWorld(key, targetZip, primaryUuid, null, true, includePlayerData)
                .thenRun(() -> {
                    Bukkit.getScheduler().runTask(context.getPlugin(), () -> {
                        TextUtils.send(sender, "<color:#e98df5>ℹ</color> <green>Successfully exported <white><world></white> to plugins/Voyage/exports/ folder.</green>", Placeholder.unparsed("world", key.asString()));
                        context.getWebhookService().notify("WORLD_EXPORT", sender, "Exported " + key.asString() + " (" + (includePlayerData ? "with data" : "clean") + ")");
                    });
                })
                .exceptionally(ex -> {
                    Bukkit.getScheduler().runTask(context.getPlugin(), () -> {
                        Throwable cause = ex instanceof java.util.concurrent.CompletionException ? ex.getCause() : ex;
                        TextUtils.send(sender, "<color:#e98df5>ℹ</color> <red>Failed to export world: <error></red>", Placeholder.unparsed("error", cause.getMessage()));
                    });
                    return null;
                });
    }
}