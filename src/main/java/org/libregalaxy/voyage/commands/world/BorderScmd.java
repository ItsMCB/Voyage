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
import org.jetbrains.annotations.NotNull;

import java.util.List;

public class BorderScmd extends VexelPlayerSubCommand<VoyageContext> {

    private final VexelArg<NamespacedKey> WORLD_ARG;
    private final VexelArg<String> SIZE_ARG;

    public BorderScmd(@NotNull VoyageContext context) {
        super(context, "border", "Sets or resets the world border size for a dimension.", "voyage.border");
        this.WORLD_ARG = VexelArg.builder("world", ArgumentTypes.namespacedKey(), NamespacedKey.class, "Target dimension to modify (e.g., 'minecraft:overworld')")
                .suggests(() -> context.getWorldManager().getLoadedWorldNames())
                .suggestionMode(VexelArg.SuggestionMode.NAMESPACED_KEY)
                .build();

        this.SIZE_ARG = VexelArg.builder("size", StringArgumentType.word(), String.class, "Diameter of the border in blocks, or 'reset' to remove the border (set to max size)")
                .suggests(List.of("reset"))
                .build();
    }

    @Override public @NotNull List<VexelArg<?>> getArguments() { return List.of(WORLD_ARG, SIZE_ARG); }

    @Override
    public void execute(@NotNull CommandContext<CommandSourceStack> ctx, @NotNull Player player) {
        NamespacedKey key = requireArg(ctx, WORLD_ARG);
        String sizeInput = requireArg(ctx, SIZE_ARG);

        World world = Bukkit.getWorld(key);
        if (world == null) {
            TextUtils.send(player, "<color:#e98df5>ℹ</color> <red>World <white><world></white> must be loaded to modify its border.</red>", Placeholder.unparsed("world", key.asString()));
            return;
        }

        boolean isReset = sizeInput.equalsIgnoreCase("reset");
        double size;

        if (isReset) {
            size = 5.9999968E7;
        } else {
            try {
                size = Double.parseDouble(sizeInput);
                if (!Double.isFinite(size) || Double.isNaN(size)) {
                    throw new NumberFormatException("Not a finite number");
                }
            } catch (NumberFormatException e) {
                TextUtils.send(player, "<color:#e98df5>ℹ</color> <red>Invalid size '<white><input></white>'. Must be a finite number or 'reset'.</red>",
                        Placeholder.unparsed("input", sizeInput));
                return;
            }
            if (size < 1.0) {
                TextUtils.send(player, "<color:#e98df5>ℹ</color> <red>Border size must be at least 1.0 block.</red>");
                return;
            }
        }

        world.getWorldBorder().setSize(size);
        world.save();

        if (isReset) {
            TextUtils.send(player, "<color:#e98df5>ℹ</color> <green>Removed world border for <white><world></white> (set to maximum size).</green>",
                    Placeholder.unparsed("world", key.asString()));
        } else {
            TextUtils.send(player, "<color:#e98df5>ℹ</color> <green>Set world border for <white><world></white> to <white><size></white> blocks.</green>",
                    Placeholder.unparsed("world", key.asString()), Placeholder.unparsed("size", formatSize(size)));
        }
    }

    private static String formatSize(double size) {
        if (size == Math.floor(size) && !Double.isInfinite(size) && !Double.isNaN(size)) {
            return String.format("%.0f", size);
        }
        return String.valueOf(size);
    }
}