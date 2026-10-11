package org.libregalaxy.voyage.commands.world;

import com.mojang.brigadier.context.CommandContext;
import io.papermc.paper.command.brigadier.CommandSourceStack;
import me.itsmcb.vexelcore.bukkit.api.framework.TextUtils;
import me.itsmcb.vexelcore.bukkit.api.framework.VexelArg;
import me.itsmcb.vexelcore.bukkit.api.framework.VexelSenderSubCommand;
import net.kyori.adventure.text.minimessage.tag.resolver.Placeholder;
import org.bukkit.Bukkit;
import org.bukkit.World;
import org.bukkit.command.CommandSender;
import org.libregalaxy.voyage.VoyageContext;
import org.libregalaxy.voyage.api.ManagedWorld;
import org.libregalaxy.voyage.api.VoyageAPI;
import org.jetbrains.annotations.NotNull;

import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

public class ListScmd extends VexelSenderSubCommand<VoyageContext> {

    public ListScmd(@NotNull VoyageContext context) {
        super(context, "list", "Displays all managed, loaded, and discovered dimensions.", "voyage.list");
    }

    @Override public @NotNull List<VexelArg<?>> getArguments() { return Collections.emptyList(); }

    @Override
    public void execute(@NotNull CommandContext<CommandSourceStack> ctx, @NotNull CommandSender sender) {
        VoyageAPI api = context.getWorldManager();
        List<ManagedWorld> trackedWorlds = api.getTrackedWorlds();
        List<World> loadedBukkitWorlds = Bukkit.getWorlds();
        List<String> allWorldNames = api.getAllWorldNames();

        Set<String> processed = new HashSet<>();
        TextUtils.send(sender, "<dark_gray><st>                    </st> <color:#e98df5>\uD83D\uDCC4 World List \uD83D\uDCC4</color> <st>                    </st> </dark_gray>");

        for (ManagedWorld mw : trackedWorlds) {
            String name = mw.getKey().asString();
            processed.add(name);

            boolean isLoaded = mw.isLoaded();
            String status = isLoaded ? "<green>Loaded</green>" : "<gray>Unloaded</gray>";
            int players = isLoaded ? mw.getBukkitWorld().map(w -> w.getPlayers().size()).orElse(0) : 0;

            // Direct interpolation ensures the command runs with the resolved dimension name
            TextUtils.send(sender, "<color:#e98df5>◈</color> <green>[M]</green> <click:run_command:'/world tp " + name + "'><hover:show_text:'<gray>Click to load and teleport to </gray><white>" + name + "</white>'><white>" + name + "</white></hover></click> <dark_gray>-</dark_gray> <gray><players> players</gray> <dark_gray>(<status>)</dark_gray>",
                    Placeholder.unparsed("players", String.valueOf(players)),
                    Placeholder.parsed("status", status)
            );
        }

        for (World w : loadedBukkitWorlds) {
            String name = w.getKey().asString();
            if (processed.add(name)) {
                TextUtils.send(sender, "<color:#e98df5>◈</color> <gray>[U]</gray> <click:run_command:'/world tp " + name + "'><hover:show_text:'<gray>Click to teleport to </gray><white>" + name + "</white>'><white>" + name + "</white></hover></click> <dark_gray>-</dark_gray> <gray><players> players</gray> <dark_gray>(<green>Loaded</green>)</dark_gray>",
                        Placeholder.unparsed("players", String.valueOf(w.getPlayers().size()))
                );
            }
        }

        for (String name : allWorldNames) {
            if (processed.add(name)) {
                TextUtils.send(sender, "<color:#e98df5>◈</color> <gray>[U]</gray> <click:suggest_command:'/world manage track " + name + "'><hover:show_text:'<gray>Click to manage </gray><white>" + name + "</white>'><gray>" + name + "</gray></hover></click> <dark_gray>-</dark_gray> <gray>0 players</gray> <dark_gray>(<gray>Unloaded</gray>)</dark_gray>");
            }
        }

        int totalManaged = trackedWorlds.size();
        int totalUnmanaged = processed.size() - totalManaged;
        int totalLoaded = loadedBukkitWorlds.size();

        TextUtils.send(sender, "<dark_gray>Managed: <white><managed></white> | Unmanaged: <white><unmanaged></white> | Loaded: <white><loaded></white></dark_gray>",
                Placeholder.unparsed("managed", String.valueOf(totalManaged)),
                Placeholder.unparsed("unmanaged", String.valueOf(totalUnmanaged)),
                Placeholder.unparsed("loaded", String.valueOf(totalLoaded))
        );
    }
}