package org.libregalaxy.voyage.commands.world;

import com.mojang.brigadier.context.CommandContext;
import io.papermc.paper.command.brigadier.CommandSourceStack;
import io.papermc.paper.command.brigadier.argument.ArgumentTypes;
import me.itsmcb.vexelcore.bukkit.api.framework.TextUtils;
import me.itsmcb.vexelcore.bukkit.api.framework.VexelArg;
import me.itsmcb.vexelcore.bukkit.api.framework.VexelPlayerSubCommand;
import net.kyori.adventure.text.minimessage.tag.resolver.Placeholder;
import org.bukkit.NamespacedKey;
import org.bukkit.entity.Player;
import org.libregalaxy.voyage.VoyageContext;
import org.libregalaxy.voyage.api.ManagedWorld;
import org.libregalaxy.voyage.api.VoyageAPI;
import org.jetbrains.annotations.NotNull;

import java.util.List;
import java.util.Optional;

public class UnloadScmd extends VexelPlayerSubCommand<VoyageContext> {

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
    public void execute(@NotNull CommandContext<CommandSourceStack> ctx, @NotNull Player player) {
        NamespacedKey key = requireArg(ctx, WORLD_ARG);

        VoyageAPI api = context.getWorldManager();

        Optional<ManagedWorld> opt = api.getManagedWorld(key);
        if (opt.isEmpty()) {
            TextUtils.send(player, " ↪ <red>World <white><world></white> is not managed by Voyage.</red>", Placeholder.unparsed("world", key.asString()));
            return;
        }

        if (!opt.get().isLoaded()) {
            TextUtils.send(player, " ↪ <red>World <white><world></white> is already offline.</red>", Placeholder.unparsed("world", key.asString()));
            return;
        }

        TextUtils.send(player, " ↪ <yellow>Unloading dimension <white><world></white>...</yellow>", Placeholder.unparsed("world", key.asString()));

        opt.get().unload(true).thenAccept(success -> {
            if (success) {
                TextUtils.send(player, " ↪ <green>Successfully saved and unmounted <white><world></white>.</green>", Placeholder.unparsed("world", key.asString()));
            } else {
                TextUtils.send(player, " ↪ <red>The server refused to unmount <white><world></white>. Disconnect local players and try again.</red>", Placeholder.unparsed("world", key.asString()));
            }
        });
    }
}