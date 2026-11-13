package org.libregalaxy.voyage.commands.world;

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
import org.jetbrains.annotations.NotNull;
import org.libregalaxy.voyage.VoyageContext;
import org.libregalaxy.voyage.api.ManagedWorld;
import org.libregalaxy.voyage.api.VoyageAPI;

import java.util.List;
import java.util.Optional;

public class UnloadScmd extends VexelSenderSubCommand<VoyageContext> {

    private final VexelArg<NamespacedKey> WORLD_ARG;

    public UnloadScmd(@NotNull VoyageContext context) {
        super(context, "unload", "Unloads a dimension safely without deleting it.", "voyage.unload");
        this.WORLD_ARG = VexelArg.builder("world", ArgumentTypes.namespacedKey(), NamespacedKey.class, "Active dimension to save and unmount from memory")
                .suggests(() -> context.getWorldManager().getLoadedTrackedWorldNames())
                .suggestionMode(VexelArg.SuggestionMode.NAMESPACED_KEY)
                .build();
    }

    @Override public @NotNull List<VexelArg<?>> getArguments() { return List.of(WORLD_ARG); }

    @Override
    public void execute(@NotNull CommandContext<CommandSourceStack> ctx, @NotNull CommandSender sender) {
        NamespacedKey key = requireArg(ctx, WORLD_ARG);

        VoyageAPI api = context.getWorldManager();

        if (api.getMainLevel() != null && key.equals(api.getMainLevel().getKey())) {
            TextUtils.send(sender, " ↪ <red>The main server level (<white><world></white>) cannot be unloaded.</red>", Placeholder.unparsed("world", key.asString()));
            return;
        }

        Optional<ManagedWorld> opt = api.getManagedWorld(key);
        if (opt.isEmpty()) {
            TextUtils.send(sender, " ↪ <red>World <white><world></white> is not managed by Voyage.</red>", Placeholder.unparsed("world", key.asString()));
            return;
        }

        if (!opt.get().isLoaded()) {
            TextUtils.send(sender, " ↪ <red>World <white><world></white> is already offline.</red>", Placeholder.unparsed("world", key.asString()));
            return;
        }

        TextUtils.send(sender, " ↪ <yellow>Unloading dimension <white><world></white>...</yellow>", Placeholder.unparsed("world", key.asString()));

        opt.get().unload(true).thenAccept(success -> {
            Bukkit.getScheduler().runTask(context.getPlugin(), () -> {
                if (success) {
                    TextUtils.send(sender, " ↪ <green>Successfully saved and unmounted <white><world></white>.</green>", Placeholder.unparsed("world", key.asString()));
                } else {
                    TextUtils.send(sender, " ↪ <red>The server refused to unmount <white><world></white>. Disconnect local players and try again.</red>", Placeholder.unparsed("world", key.asString()));
                }
            });
        }).exceptionally(ex -> {
            Bukkit.getScheduler().runTask(context.getPlugin(), () -> {
                Throwable cause = ex instanceof java.util.concurrent.CompletionException ? ex.getCause() : ex;
                TextUtils.send(sender, " ↪ <red>Failed to unload <white><world></white>: <error></red>",
                        Placeholder.unparsed("world", key.asString()),
                        Placeholder.unparsed("error", cause != null && cause.getMessage() != null ? cause.getMessage() : "unknown error"));
            });
            return null;
        });
    }
}