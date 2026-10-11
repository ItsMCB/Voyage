package org.libregalaxy.voyage.commands.world;

import com.mojang.brigadier.arguments.StringArgumentType;
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
import org.libregalaxy.voyage.api.portal.CoordinateStrategy;
import org.libregalaxy.voyage.api.portal.PortalGenerator;
import org.libregalaxy.voyage.api.portal.PortalLink;
import org.libregalaxy.voyage.api.portal.PortalType;
import org.libregalaxy.voyage.api.portal.generator.NoneGenerator;
import org.libregalaxy.voyage.api.portal.generator.StructureGenerator;
import org.libregalaxy.voyage.api.portal.generator.VanillaNetherGenerator;
import org.libregalaxy.voyage.api.portal.strategy.FixedSpawnStrategy;
import org.libregalaxy.voyage.api.portal.strategy.VanillaScalingStrategy;
import org.jetbrains.annotations.NotNull;

import java.util.*;

public class PortalLinkScmd extends VexelPlayerSubCommand<VoyageContext> {

    private final VexelArg<String> ACTION_ARG;
    private final VexelArg<NamespacedKey> WORLD_ARG;
    private final VexelArg<String> TYPE_ARG;
    private final VexelArg<NamespacedKey> TARGET_ARG;
    private final VexelArg<String> STRATEGY_ARG;
    private final VexelArg<String> GENERATOR_ARG;
    private final VexelArg<String> EXTRA_ARG;

    public PortalLinkScmd(@NotNull VoyageContext context) {
        super(context, "portal-link", "Link dimension portals to one another.", "voyage.portallink");
        this.ACTION_ARG = VexelArg.builder("action", StringArgumentType.word(), String.class, "Action: create or break")
                .suggests(List.of("create", "break"))
                .build();

        this.WORLD_ARG = VexelArg.builder("source_world", ArgumentTypes.namespacedKey(), NamespacedKey.class, "Origin dimension where the portal resides")
                .suggests(() -> context.getWorldManager().getTrackedWorldNames())
                .suggestionMode(VexelArg.SuggestionMode.NAMESPACED_KEY)
                .build();

        this.TYPE_ARG = VexelArg.builder("type", StringArgumentType.word(), String.class, "Type of portal block/trigger (NETHER, END, CUSTOM)")
                .suggests(List.of("NETHER", "END", "CUSTOM"))
                .build();

        this.TARGET_ARG = VexelArg.builder("target_world", ArgumentTypes.namespacedKey(), NamespacedKey.class, "Destination dimension to route players to")
                .optional()
                .suggests(() -> context.getWorldManager().getAllWorldNames())
                .suggestionMode(VexelArg.SuggestionMode.NAMESPACED_KEY)
                .build();

        this.STRATEGY_ARG = VexelArg.builder("strategy", StringArgumentType.word(), String.class, "Coordinate mapping: TARGET_SPAWN_POINT or VANILLA_SCALING")
                .optional()
                .suggests(List.of("TARGET_SPAWN_POINT", "VANILLA_SCALING"))
                .build();

        this.GENERATOR_ARG = VexelArg.builder("generator", StringArgumentType.word(), String.class, "Creation rule: NONE, VANILLA_NETHER, or STRUCTURE")
                .optional()
                .suggests(List.of("NONE", "VANILLA_NETHER", "STRUCTURE"))
                .build();

        this.EXTRA_ARG = VexelArg.builder("extra_data", StringArgumentType.string(), String.class, "Scale factor ratio (e.g. 8.0) if VANILLA_SCALING, or Structure Key if STRUCTURE")
                .optional()
                .build();
    }

    @Override public @NotNull List<VexelArg<?>> getArguments() {
        return List.of(ACTION_ARG, WORLD_ARG, TYPE_ARG, TARGET_ARG, STRATEGY_ARG, GENERATOR_ARG, EXTRA_ARG);
    }

    @Override
    public void execute(@NotNull CommandContext<CommandSourceStack> ctx, @NotNull Player player) {
        String actionStr = requireArg(ctx, ACTION_ARG).toLowerCase(Locale.ROOT);
        NamespacedKey worldKey = requireArg(ctx, WORLD_ARG);
        String typeStr = requireArg(ctx, TYPE_ARG).toUpperCase(Locale.ROOT);

        PortalType portalType;
        try {
            portalType = PortalType.valueOf(typeStr);
        } catch (IllegalArgumentException e) {
            TextUtils.send(player, "<color:#e98df5>ℹ</color> <red>Unknown portal type <white><type></white>. Valid values: NETHER, END, CUSTOM</red>", Placeholder.unparsed("type", typeStr));
            return;
        }

        VoyageAPI api = context.getWorldManager();
        Optional<ManagedWorld> optMw = api.getManagedWorld(worldKey);

        if (optMw.isEmpty()) {
            TextUtils.send(player, "<color:#e98df5>ℹ</color> <red>Source world <white><world></white> is not managed by Voyage.</red>", Placeholder.unparsed("world", worldKey.asString()));
            return;
        }

        if ("break".equals(actionStr)) {
            optMw.get().unlinkPortal(portalType);
            TextUtils.send(player, "<color:#e98df5>ℹ</color> <green>Portal link unlinked successfully for <white><type></white>!</green>", Placeholder.unparsed("type", portalType.name()));
            context.getWebhookService().notify("PORTAL_LINK", player, "Unlinked " + portalType.name() + " portal for " + worldKey.asString());
            return;
        }

        if ("create".equals(actionStr)) {
            NamespacedKey targetKey = optionalArg(ctx, TARGET_ARG);
            String strategyStr = optionalArg(ctx, STRATEGY_ARG);
            String generatorStr = optionalArg(ctx, GENERATOR_ARG);
            String extra = optionalArg(ctx, EXTRA_ARG);

            if (targetKey == null || strategyStr == null || generatorStr == null) {
                TextUtils.send(player, "<color:#e98df5>ℹ</color> <red>Missing arguments. Usage: /world portal-link create <world> <type> <target> <strategy> <generator> [extra]</red>");
                return;
            }

            strategyStr = strategyStr.toUpperCase(Locale.ROOT);
            generatorStr = generatorStr.toUpperCase(Locale.ROOT);

            CoordinateStrategy strategy;
            if ("VANILLA_SCALING".equals(strategyStr)) {
                double factor = 1.0;
                if (extra != null) {
                    try {
                        factor = Double.parseDouble(extra);
                        if (factor <= 0.0) {
                            TextUtils.send(player, "<color:#e98df5>ℹ</color> <yellow>Scale factor must be a positive number; defaulting to 1.0.</yellow>");
                            factor = 1.0;
                        }
                    } catch (NumberFormatException e) {
                        TextUtils.send(player, "<color:#e98df5>ℹ</color> <yellow>Invalid scale factor; defaulting to 1.0.</yellow>");
                    }
                }
                strategy = new VanillaScalingStrategy(factor);
            } else {
                strategy = new FixedSpawnStrategy();
            }

            PortalGenerator generator;
            if ("VANILLA_NETHER".equals(generatorStr)) {
                generator = new VanillaNetherGenerator();
            } else if ("STRUCTURE".equals(generatorStr)) {
                NamespacedKey sKey = NamespacedKey.minecraft("nether_portal");
                if (extra != null) {
                    NamespacedKey parsed = NamespacedKey.fromString(extra);
                    if (parsed != null) {
                        sKey = parsed;
                    } else {
                        TextUtils.send(player, "<color:#e98df5>ℹ</color> <yellow>Invalid structure key; defaulting to minecraft:nether_portal.</yellow>");
                    }
                }
                generator = new StructureGenerator(sKey);
            } else {
                generator = new NoneGenerator();
            }

            optMw.get().linkPortal(portalType, new PortalLink(targetKey, strategy, generator));

            TextUtils.send(player, "<color:#e98df5>ℹ</color> <green>Dimension portal logic linked successfully!</green>");
            TextUtils.send(player, "   <gray><italic>(Note: Links route the entire dimension, not individual portals. Origin portals snap to target portal by searching a 128-block radius.)</italic></gray>");
            context.getWebhookService().notify("PORTAL_LINK", player, "Linked " + portalType.name() + " portal from " + worldKey.asString() + " to " + targetKey.asString());
        } else {
            TextUtils.send(player, "<color:#e98df5>ℹ</color> <red>Invalid action: <white><action></white>. Use 'create' or 'break'.</red>", Placeholder.unparsed("action", actionStr));
        }
    }
}