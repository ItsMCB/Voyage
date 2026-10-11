package org.libregalaxy.voyage.commands.world;

import com.mojang.brigadier.arguments.StringArgumentType;
import com.mojang.brigadier.context.CommandContext;
import io.papermc.paper.command.brigadier.CommandSourceStack;
import io.papermc.paper.command.brigadier.argument.ArgumentTypes;
import me.itsmcb.vexelcore.bukkit.api.framework.TaskProgress;
import me.itsmcb.vexelcore.bukkit.api.framework.TextUtils;
import me.itsmcb.vexelcore.bukkit.api.framework.VexelArg;
import me.itsmcb.vexelcore.bukkit.api.framework.VexelPlayerSubCommand;
import net.kyori.adventure.text.minimessage.tag.resolver.Placeholder;
import org.bukkit.Bukkit;
import org.bukkit.NamespacedKey;
import org.bukkit.World;
import org.bukkit.entity.Player;
import org.jetbrains.annotations.NotNull;
import org.libregalaxy.voyage.VoyageContext;
import org.libregalaxy.voyage.core.VoyageWorldManager;

import java.util.List;
import java.util.concurrent.CompletableFuture;

public class CloneScmd extends VexelPlayerSubCommand<VoyageContext> {

    private final VexelArg<NamespacedKey> SOURCE_ARG;
    private final VexelArg<NamespacedKey> TARGET_ARG;
    private final VexelArg<String> OVERWRITE_ARG;

    public CloneScmd(@NotNull VoyageContext context) {
        super(context, "clone", "Duplicates an entire dimension structure.", "voyage.clone");
        this.SOURCE_ARG = VexelArg.builder("source", ArgumentTypes.namespacedKey(), NamespacedKey.class, "Existing managed dimension to copy blocks from")
                .suggests(() -> context.getWorldManager().getTrackedWorldNames())
                .suggestionMode(VexelArg.SuggestionMode.NAMESPACED_KEY)
                .build();

        this.TARGET_ARG = VexelArg.builder("target", ArgumentTypes.namespacedKey(), NamespacedKey.class, "New dimension identifier (custom:overworld_copy)")
                .build();

        this.OVERWRITE_ARG = VexelArg.builder("overwrite", StringArgumentType.word(), String.class, "Overwrite if target directory already exists")
                .optional()
                .suggests(List.of("--overwrite"))
                .build();
    }

    @Override public @NotNull List<String> getAliases() { return List.of("duplicate"); }
    @Override public @NotNull List<VexelArg<?>> getArguments() { return List.of(SOURCE_ARG, TARGET_ARG, OVERWRITE_ARG); }

    @Override
    public void execute(@NotNull CommandContext<CommandSourceStack> ctx, @NotNull Player player) {
        NamespacedKey sourceKey = requireArg(ctx, SOURCE_ARG);
        NamespacedKey targetKey = requireArg(ctx, TARGET_ARG);
        boolean overwrite = "--overwrite".equalsIgnoreCase(optionalArg(ctx, OVERWRITE_ARG));

        if (sourceKey.equals(targetKey)) {
            TextUtils.send(player, "<color:#e98df5>ℹ</color> <red>Source and target worlds cannot be the same.</red>");
            return;
        }

        VoyageWorldManager manager = context.getWorldManager();

        if (manager.getManagedWorld(sourceKey).isEmpty()) {
            TextUtils.send(player, "<color:#e98df5>ℹ</color> <red>Source world <white><world></white> is not managed by Voyage.</red>", Placeholder.unparsed("world", sourceKey.asString()));
            return;
        }

        if (manager.worldExists(targetKey)) {
            if (manager.getManagedWorld(targetKey).isEmpty()) {
                TextUtils.send(player, "<color:#e98df5>ℹ</color> <red>Target world <white><target></white> already exists and is NOT managed by Voyage.</red>", Placeholder.unparsed("target", targetKey.asString()));
                return;
            }
            if (!overwrite) {
                TextUtils.send(player, "<color:#e98df5>ℹ</color> <red>Target world <white><target></white> already exists. Append <white>--overwrite</white> to replace it.</red>", Placeholder.unparsed("target", targetKey.asString()));
                return;
            }
        }

        CompletableFuture<Boolean> wipeTargetFuture = (manager.worldExists(targetKey) && overwrite)
                ? manager.deleteWorld(targetKey, null)
                : CompletableFuture.completedFuture(true);

        wipeTargetFuture.thenAccept(wiped -> {
            if (!wiped && overwrite) {
                TextUtils.send(player, "<color:#e98df5>ℹ</color> <red>Failed to overwrite target dimension.</red>");
                return;
            }

            Bukkit.getScheduler().runTask(context.getPlugin(), () -> {
                TaskProgress progress = new TaskProgress(context.getPlugin(), player, TaskProgress.Strategy.ACTIONBAR, "<color:#e98df5>ℹ</color> ", 10L);
                progress.start(TextUtils.mm("<yellow>Cloning world <world>...</yellow>", Placeholder.unparsed("world", sourceKey.asString())));

                final long[] lastUpdate = new long[1];

                manager.cloneWorld(sourceKey, targetKey, true, p -> {
                    long now = System.currentTimeMillis();
                    if (now - lastUpdate[0] > 100 || p >= 1.0) {
                        lastUpdate[0] = now;
                        Bukkit.getScheduler().runTask(context.getPlugin(), () -> {
                            progress.update(TextUtils.mm("<yellow>Cloning data... " + String.format("%.0f%%", p * 100) + "</yellow>"));
                        });
                    }
                }).thenAccept(clonedWorld -> {
                    Bukkit.getScheduler().runTask(context.getPlugin(), () -> {
                        progress.complete(TextUtils.mm("<green>Successfully cloned to <target>!</green>", Placeholder.unparsed("target", targetKey.asString())));
                        TextUtils.send(player, "<color:#e98df5>ℹ</color> <green>Successfully cloned to <white><target></white>!</green>", Placeholder.unparsed("target", targetKey.asString()));

                        context.getWebhookService().notify("WORLD_CLONE", player, "Cloned dimension " + sourceKey.asString() + " to " + targetKey.asString());

                        World newBukkitWorld = Bukkit.getWorld(targetKey);
                        if (newBukkitWorld != null) {
                            player.teleportAsync(newBukkitWorld.getSpawnLocation());
                        }
                    });
                }).exceptionally(ex -> {
                    Bukkit.getScheduler().runTask(context.getPlugin(), () -> {
                        Throwable cause = ex instanceof java.util.concurrent.CompletionException ? ex.getCause() : ex;
                        progress.complete(TextUtils.mm("<red>Failed to clone to <target></red>", Placeholder.unparsed("target", targetKey.asString())));
                        TextUtils.send(player, "<color:#e98df5>ℹ</color> <red>Failed to clone world <target>: <error></red>",
                                Placeholder.unparsed("target", targetKey.asString()), Placeholder.unparsed("error", cause.getMessage()));
                    });
                    return null;
                });
            });
        }).exceptionally(ex -> {
            Throwable cause = ex instanceof java.util.concurrent.CompletionException ? ex.getCause() : ex;
            TextUtils.send(player, "<color:#e98df5>ℹ</color> <red>Clone aborted: <error></red>", Placeholder.unparsed("error", cause.getMessage()));
            return null;
        });
    }
}