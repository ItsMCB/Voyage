package org.libregalaxy.voyage.commands.world;

import com.mojang.brigadier.arguments.StringArgumentType;
import com.mojang.brigadier.context.CommandContext;
import io.papermc.paper.command.brigadier.CommandSourceStack;
import io.papermc.paper.command.brigadier.argument.ArgumentTypes;
import me.itsmcb.vexelcore.bukkit.api.framework.TextUtils;
import me.itsmcb.vexelcore.bukkit.api.framework.VexelArg;
import me.itsmcb.vexelcore.bukkit.api.framework.VexelPlayerSubCommand;
import net.kyori.adventure.text.minimessage.tag.resolver.Placeholder;
import org.bukkit.Bukkit;
import org.bukkit.NamespacedKey;
import org.bukkit.World;
import org.bukkit.entity.Player;
import org.libregalaxy.voyage.VoyageContext;
import org.libregalaxy.voyage.api.ManagedWorld;
import org.libregalaxy.voyage.api.VoyageAPI;
import org.libregalaxy.voyage.api.builder.WorldBuilder;
import org.jetbrains.annotations.NotNull;

import java.util.List;
import java.util.Locale;
import java.util.concurrent.CompletableFuture;

public class ManageScmd extends VexelPlayerSubCommand<VoyageContext> {

    private final VexelArg<String> ACTION_ARG;
    private final VexelArg<NamespacedKey> WORLD_ARG;

    public ManageScmd(@NotNull VoyageContext context) {
        super(context, "manage", "Start or stop tracking a world configuration.", "voyage.manage");
        this.ACTION_ARG = VexelArg.builder("action", StringArgumentType.word(), String.class, "State: 'track' (sync to YAML) or 'untrack' (stop tracking)")
                .suggests(List.of("track", "untrack"))
                .build();

        this.WORLD_ARG = VexelArg.builder("world", ArgumentTypes.namespacedKey(), NamespacedKey.class, "Target dimension")
                .suggests(() -> context.getWorldManager().getAllWorldNames())
                .suggestionMode(VexelArg.SuggestionMode.NAMESPACED_KEY)
                .build();
    }

    @Override public @NotNull List<VexelArg<?>> getArguments() { return List.of(ACTION_ARG, WORLD_ARG); }

    @Override
    public void execute(@NotNull CommandContext<CommandSourceStack> ctx, @NotNull Player player) {
        String action = requireArg(ctx, ACTION_ARG).toLowerCase(Locale.ROOT);
        NamespacedKey key = requireArg(ctx, WORLD_ARG);

        VoyageAPI api = context.getWorldManager();

        switch (action) {
            case "track" -> {
                if (api.getManagedWorld(key).isPresent()) {
                    TextUtils.send(player, "<color:#e98df5>ℹ</color> <yellow>World <white><world></white> is already managed.</yellow>", Placeholder.unparsed("world", key.asString()));
                    return;
                }

                // Inherit environment of currently unmanaged worlds to prevent reset
                WorldBuilder builder = api.builder(key).importExisting(true).autoLoad(false);
                World loadedWorld = Bukkit.getWorld(key);
                if (loadedWorld != null) {
                    builder.environment(loadedWorld.getEnvironment());
                }

                builder.create().thenRun(() -> {
                    Bukkit.getScheduler().runTask(context.getPlugin(), () -> {
                        TextUtils.send(player, "<color:#e98df5>ℹ</color> <green>Now tracking world <white><world></white>.</green> <gray>(Use <white>/world tp</white> to load it)</gray>", Placeholder.unparsed("world", key.asString()));
                    });
                }).exceptionally(ex -> {
                    Bukkit.getScheduler().runTask(context.getPlugin(), () -> {
                        Throwable cause = ex instanceof java.util.concurrent.CompletionException ? ex.getCause() : ex;
                        TextUtils.send(player, "<color:#e98df5>ℹ</color> <red>Failed to track world <white><world></white>: <error></red>",
                                Placeholder.unparsed("world", key.asString()), Placeholder.unparsed("error", cause.getMessage()));
                    });
                    return null;
                });
            }
            case "untrack" -> {
                if (api.getManagedWorld(key).isEmpty()) {
                    TextUtils.send(player, "<color:#e98df5>ℹ</color> <red>World <white><world></white> is not currently managed.</red>", Placeholder.unparsed("world", key.asString()));
                    return;
                }

                CompletableFuture<Void> untrackFuture;
                ManagedWorld mw = api.getManagedWorld(key).get();
                if (mw.isLoaded()) {
                    TextUtils.send(player, "<color:#e98df5>ℹ</color> <yellow>Unloading <white><world></white> before untracking...</yellow>", Placeholder.unparsed("world", key.asString()));
                    untrackFuture = mw.unload(true).thenCompose(success -> {
                        if (success) {
                            return api.untrackWorld(key);
                        } else {
                            return CompletableFuture.failedFuture(new IllegalStateException("Failed to unload world."));
                        }
                    });
                } else {
                    untrackFuture = api.untrackWorld(key);
                }

                untrackFuture.thenRun(() -> {
                    TextUtils.send(player, "<color:#e98df5>ℹ</color> <green>Stopped tracking world <white><world></white>.</green>", Placeholder.unparsed("world", key.asString()));
                }).exceptionally(ex -> {
                    Bukkit.getScheduler().runTask(context.getPlugin(), () -> {
                        Throwable cause = ex instanceof java.util.concurrent.CompletionException ? ex.getCause() : ex;
                        TextUtils.send(player, "<color:#e98df5>ℹ</color> <red>Failed to untrack world <white><world></white>: <error></red>",
                                Placeholder.unparsed("world", key.asString()), Placeholder.unparsed("error", cause.getMessage()));
                    });
                    return null;
                });
            }
            default -> TextUtils.send(player, "<color:#e98df5>ℹ</color> <red>Invalid action. Use 'track' or 'untrack'.</red>");
        }
    }
}