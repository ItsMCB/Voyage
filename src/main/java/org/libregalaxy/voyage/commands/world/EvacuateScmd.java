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
import org.bukkit.World;
import org.bukkit.entity.Player;
import org.libregalaxy.voyage.VoyageContext;
import org.jetbrains.annotations.NotNull;

import java.util.List;

public class EvacuateScmd extends VexelPlayerSubCommand<VoyageContext> {

    private final VexelArg<NamespacedKey> WORLD_ARG;
    private final VexelArg<NamespacedKey> TARGET_ARG;

    public EvacuateScmd(@NotNull VoyageContext context) {
        super(context, "evacuate", "Kicks all players from a world to the default fallback world.", "voyage.evacuate");
        this.WORLD_ARG = VexelArg.builder("world", ArgumentTypes.namespacedKey(), NamespacedKey.class, "Target dimension to forcefully empty of players")
                .suggests(() -> context.getWorldManager().getLoadedWorldNames())
                .suggestionMode(VexelArg.SuggestionMode.NAMESPACED_KEY)
                .build();

        this.TARGET_ARG = VexelArg.builder("targetWorld", ArgumentTypes.namespacedKey(), NamespacedKey.class, "Optional target world to evacuate to")
                .optional()
                .suggests(() -> context.getWorldManager().getLoadedWorldNames())
                .suggestionMode(VexelArg.SuggestionMode.NAMESPACED_KEY)
                .build();
    }

    @Override public @NotNull List<VexelArg<?>> getArguments() { return List.of(WORLD_ARG, TARGET_ARG); }

    @Override
    public void execute(@NotNull CommandContext<CommandSourceStack> ctx, @NotNull Player player) {
        NamespacedKey key = requireArg(ctx, WORLD_ARG);
        NamespacedKey targetKey = optionalArg(ctx, TARGET_ARG);

        World world = Bukkit.getWorld(key);
        if (world == null) {
            TextUtils.send(player, "<color:#e98df5>ℹ</color> <red>World <white><world></white> is not currently loaded.</red>", Placeholder.unparsed("world", key.asString()));
            return;
        }

        World fallback = targetKey != null ? Bukkit.getWorld(targetKey) : context.getWorldManager().determineEvacuationWorld(world);

        if (fallback == null) {
            TextUtils.send(player, "<color:#e98df5>ℹ</color> <red>No fallback world available to evacuate players to!</red>");
            return;
        }

        List<Player> players = List.copyOf(world.getPlayers());
        if (players.isEmpty()) {
            TextUtils.send(player, "<color:#e98df5>ℹ</color> <yellow>World <white><world></white> is already empty.</yellow>", Placeholder.unparsed("world", key.asString()));
            return;
        }

        for (Player p : players) {
            p.teleportAsync(fallback.getSpawnLocation());
            TextUtils.send(p, "<color:#e98df5>ℹ</color> <yellow>You have been forcefully evacuated to <white><world></white>.</yellow>", Placeholder.unparsed("world", fallback.getKey().asString()));
        }

        TextUtils.send(player, "<color:#e98df5>ℹ</color> <green>Evacuated <white><count></white> players from <white><world></white> to <white><fallback></white>.</green>",
                Placeholder.unparsed("count", String.valueOf(players.size())),
                Placeholder.unparsed("world", key.asString()),
                Placeholder.unparsed("fallback", fallback.getKey().asString()));
    }
}