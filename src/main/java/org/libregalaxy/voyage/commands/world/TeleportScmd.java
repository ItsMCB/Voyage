package org.libregalaxy.voyage.commands.world;

import com.mojang.brigadier.arguments.StringArgumentType;
import com.mojang.brigadier.context.CommandContext;
import io.papermc.paper.command.brigadier.CommandSourceStack;
import io.papermc.paper.command.brigadier.argument.ArgumentTypes;
import io.papermc.paper.command.brigadier.argument.resolvers.selector.PlayerSelectorArgumentResolver;
import me.itsmcb.vexelcore.bukkit.api.framework.TextUtils;
import me.itsmcb.vexelcore.bukkit.api.framework.VexelArg;
import me.itsmcb.vexelcore.bukkit.api.framework.VexelPlayerSubCommand;
import net.kyori.adventure.text.minimessage.tag.resolver.Placeholder;
import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.NamespacedKey;
import org.bukkit.World;
import org.bukkit.entity.Player;
import org.libregalaxy.voyage.VoyageContext;
import org.libregalaxy.voyage.api.ManagedWorld;
import org.libregalaxy.voyage.api.VoyageAPI;
import org.jetbrains.annotations.NotNull;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

public class TeleportScmd extends VexelPlayerSubCommand<VoyageContext> {

    private final VexelArg<NamespacedKey> WORLD_ARG;
    private final VexelArg<PlayerSelectorArgumentResolver> PLAYER_ARG;
    private final VexelArg<String> STRATEGY_ARG;

    public TeleportScmd(@NotNull VoyageContext context) {
        super(context, "teleport", "Teleport self or others into a different world.", "voyage.teleport");
        this.WORLD_ARG = VexelArg.builder("world", ArgumentTypes.namespacedKey(), NamespacedKey.class, "Destination dimension to travel to")
                .suggests(() -> context.getWorldManager().getAllWorldNames())
                .suggestionMode(VexelArg.SuggestionMode.NAMESPACED_KEY)
                .build();

        this.PLAYER_ARG = VexelArg.builder("player", ArgumentTypes.player(), PlayerSelectorArgumentResolver.class, "Player to teleport (leave empty to teleport yourself)")
                .optional()
                .build();

        this.STRATEGY_ARG = VexelArg.builder("strategy", StringArgumentType.word(), String.class, "Teleport strategy: SPAWN_POINT or LOG_OFF_LOCATION")
                .optional()
                .suggests(List.of("SPAWN_POINT", "LOG_OFF_LOCATION"))
                .build();
    }

    @Override public @NotNull List<String> getAliases() { return List.of("tp"); }
    @Override public @NotNull List<VexelArg<?>> getArguments() { return List.of(WORLD_ARG, PLAYER_ARG, STRATEGY_ARG); }

    @Override
    public void execute(@NotNull CommandContext<CommandSourceStack> ctx, @NotNull Player player) {
        NamespacedKey key = requireArg(ctx, WORLD_ARG);
        PlayerSelectorArgumentResolver resolver = optionalArg(ctx, PLAYER_ARG);
        String strategyStr = optionalArg(ctx, STRATEGY_ARG);

        List<Player> targets = new ArrayList<>();
        if (resolver != null) {
            try {
                targets.addAll(resolver.resolve(ctx.getSource()));
                if (targets.isEmpty()) {
                    TextUtils.send(player, "<color:#e98df5>ℹ</color> <red>Player not found on the server.</red>");
                    return;
                }
            } catch (Exception e) {
                TextUtils.send(player, "<color:#e98df5>ℹ</color> <red>Player not found on the server.</red>");
                return;
            }
        } else {
            targets.add(player);
        }

        boolean specifySpawn = "SPAWN_POINT".equalsIgnoreCase(strategyStr) || "SPAWN".equalsIgnoreCase(strategyStr);
        VoyageAPI api = context.getWorldManager();
        World bukkitWorld = Bukkit.getWorld(key);

        if (bukkitWorld == null) {
            Optional<ManagedWorld> mw = api.getManagedWorld(key);
            if (mw.isPresent()) {
                TextUtils.send(player, "<color:#e98df5>ℹ</color> <yellow>Loading dimension <white><world></white> and teleporting you to it...</yellow>", Placeholder.unparsed("world", key.asString()));
                mw.get().load().thenAccept(loaded -> teleportTargets(player, targets, loaded, key, specifySpawn, api));
            } else {
                TextUtils.send(player, "<color:#e98df5>ℹ</color> <red>Dimension <white><world></white> is not loaded or managed or Voyage.</red>", Placeholder.unparsed("world", key.asString()));
            }
            return;
        }

        teleportTargets(player, targets, bukkitWorld, key, specifySpawn, api);
    }

    private void teleportTargets(Player executor, List<Player> targets, World targetWorld, NamespacedKey requestKey, boolean specifySpawn, VoyageAPI api) {
        for (Player target : targets) {
            Location loc = targetWorld.getSpawnLocation();
            boolean teleportedToLogOff = false;
            if (!specifySpawn) {
                Optional<VoyageAPI.OfflineLocation> optLoc = api.getPlayerLogOffLocation(targetWorld.getKey(), target.getUniqueId());
                if (optLoc.isPresent()) {
                    VoyageAPI.OfflineLocation offLoc = optLoc.get();
                    // Guarantee destination is strictly inside targetWorld
                    if (offLoc.worldKey().equals(targetWorld.getKey())) {
                        loc = new Location(targetWorld, offLoc.x(), offLoc.y(), offLoc.z(), offLoc.yaw(), offLoc.pitch());
                        teleportedToLogOff = true;
                    }
                }
                if (!teleportedToLogOff) {
                    TextUtils.send(executor, "<color:#e98df5>ℹ</color> <yellow>No log-off location found for <player> in <world>. Falling back to spawn point.</yellow>",
                            Placeholder.unparsed("player", target.getName()),
                            Placeholder.unparsed("world", targetWorld.getKey().asString()));
                }
            }
            target.teleportAsync(loc);
            if (teleportedToLogOff) {
                TextUtils.send(executor, "<color:#e98df5>\uD83C\uDF00</color> <green>Teleported <player> to <white><world></white> at their last log-off location.</green>",
                        Placeholder.unparsed("player", target.getName()), Placeholder.unparsed("world", targetWorld.getKey().asString()));
            } else {
                TextUtils.send(executor, "<color:#e98df5>\uD83C\uDF00</color> <green>Teleported <player> to <white><world></white> at the world spawn point.</green>",
                        Placeholder.unparsed("player", target.getName()), Placeholder.unparsed("world", targetWorld.getKey().asString()));
            }
        }
    }
}