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
import org.bukkit.World;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Player;
import org.libregalaxy.voyage.VoyageContext;
import org.libregalaxy.voyage.api.ManagedWorld;
import org.libregalaxy.voyage.api.VoyageAPI;
import org.jetbrains.annotations.NotNull;

import java.util.List;
import java.util.Optional;

public class DeleteScmd extends VexelSenderSubCommand<VoyageContext> {

    private final VexelArg<NamespacedKey> WORLD_ARG;
    private final VexelArg<String> CONFIRM_ARG;

    public DeleteScmd(@NotNull VoyageContext context) {
        super(context, "delete", "Unloads and permanently deletes a dimension.", "voyage.delete");
        this.WORLD_ARG = VexelArg.builder("world", ArgumentTypes.namespacedKey(), NamespacedKey.class, "Managed dimension to permanently wipe from disk")
                .suggests(() -> context.getWorldManager().getTrackedWorldNames())
                .suggestionMode(VexelArg.SuggestionMode.NAMESPACED_KEY)
                .build();

        this.CONFIRM_ARG = VexelArg.builder("confirm", StringArgumentType.word(), String.class, "Confirms irreversible deletion")
                .optional()
                .suggests(List.of("--confirm"))
                .build();
    }

    @Override public @NotNull List<VexelArg<?>> getArguments() { return List.of(WORLD_ARG, CONFIRM_ARG); }

    @Override
    public void execute(@NotNull CommandContext<CommandSourceStack> ctx, @NotNull CommandSender sender) {
        NamespacedKey key = requireArg(ctx, WORLD_ARG);
        String confirm = optionalArg(ctx, CONFIRM_ARG);

        if (!"--confirm".equals(confirm)) {
            TextUtils.send(sender, "<color:#e98df5>ℹ</color> <red>Deleting a world is permanent. Run</red> <white>/world delete <world> --confirm</white> <red>to proceed.</red>",
                    Placeholder.unparsed("world", key.asString()));
            return;
        }

        VoyageAPI api = context.getWorldManager();

        Optional<ManagedWorld> mw = api.getManagedWorld(key);
        if (mw.isEmpty()) {
            TextUtils.send(sender, "<color:#e98df5>ℹ</color> <red>World <white><world></white> is not managed by Voyage. Only managed worlds can be deleted.</red>",
                    Placeholder.unparsed("world", key.asString()));
            return;
        }

        World bukkitWorld = Bukkit.getWorld(key);
        World fallback = context.getWorldManager().determineEvacuationWorld(bukkitWorld != null ? bukkitWorld : api.getMainLevel());
        NamespacedKey fallbackKey = fallback != null ? fallback.getKey() : null;

        TextUtils.send(sender, "<color:#e98df5>ℹ</color> <yellow>Evacuating players and deleting world <white><world></white>...</yellow>",
                Placeholder.unparsed("world", key.asString()));

        api.deleteWorld(key, fallbackKey).thenAccept(success -> {
            Bukkit.getScheduler().runTask(context.getPlugin(), () -> {
                if (sender instanceof Player p && !p.isOnline()) return;

                if (success) {
                    TextUtils.send(sender, "<color:#e98df5>ℹ</color> <green>Successfully deleted dimension <white><world></white>.</green>",
                            Placeholder.unparsed("world", key.asString()));
                    context.getWebhookService().notify("WORLD_DELETE", sender, "Deleted dimension " + key.asString());
                } else {
                    TextUtils.send(sender, "<color:#e98df5>ℹ</color> <red>Failed to delete <white><world></white>. Deletion was cancelled by a plugin, could not be unloaded, or disk files were locked.</red>",
                            Placeholder.unparsed("world", key.asString()));
                }
            });
        });
    }
}