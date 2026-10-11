package org.libregalaxy.voyage.commands.world;

import com.mojang.brigadier.arguments.StringArgumentType;
import com.mojang.brigadier.context.CommandContext;
import io.papermc.paper.command.brigadier.CommandSourceStack;
import io.papermc.paper.command.brigadier.argument.ArgumentTypes;
import me.itsmcb.vexelcore.bukkit.api.framework.FlagParser;
import me.itsmcb.vexelcore.bukkit.api.framework.TextUtils;
import me.itsmcb.vexelcore.bukkit.api.framework.VexelArg;
import me.itsmcb.vexelcore.bukkit.api.framework.VexelSenderSubCommand;
import net.kyori.adventure.text.minimessage.tag.resolver.Placeholder;
import org.bukkit.Bukkit;
import org.bukkit.NamespacedKey;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Player;
import org.libregalaxy.voyage.VoyageContext;
import org.libregalaxy.voyage.api.VoyageAPI;
import org.jetbrains.annotations.NotNull;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

public class ProfileLinkScmd extends VexelSenderSubCommand<VoyageContext> {

    private final VexelArg<String> ACTION_ARG;
    private final VexelArg<NamespacedKey> WORLD_ARG;
    private final VexelArg<String> ARG3;
    private final VexelArg<String> FLAGS_ARG;

    public ProfileLinkScmd(@NotNull VoyageContext context) {
        super(context, "profile-link", "Links or isolates player data/inventories between worlds.", "voyage.profilelink");

        this.ACTION_ARG = VexelArg.builder("action", StringArgumentType.word(), String.class, "Action: create or break")
                .suggests(List.of("create", "break"))
                .build();

        this.WORLD_ARG = VexelArg.builder("world", ArgumentTypes.namespacedKey(), NamespacedKey.class, "Target dimension")
                .suggests(() -> context.getWorldManager().getAllWorldNames())
                .suggestionMode(VexelArg.SuggestionMode.NAMESPACED_KEY)
                .build();

        this.ARG3 = VexelArg.builder("profile_or_clone", StringArgumentType.word(), String.class, "Target world/profile ID (create) or clone data true/false (break)")
                .optional()
                .suggestsContextual(ctx -> {
                    List<String> options = new ArrayList<>();
                    String action = optionalArg(ctx, ACTION_ARG);
                    if (action == null) return List.of();

                    if ("break".equalsIgnoreCase(action)) {
                        options.addAll(List.of("true", "false"));
                    } else if ("create".equalsIgnoreCase(action)) {
                        options.add("none");
                        org.bukkit.configuration.ConfigurationSection groups = context.getPlugin().getConfig().getConfigurationSection("world-groups");
                        if (groups != null) {
                            options.addAll(groups.getKeys(false));
                        }
                        options.addAll(context.getWorldManager().getAllWorldNames());
                    }
                    return options;
                })
                .build();

        this.FLAGS_ARG = VexelArg.builder("flags", StringArgumentType.greedyString(), String.class, "Strategy and/or --dry-run flag")
                .optional()
                .suggests(List.of("SOURCE_PRIORITY", "DESTINATION_PRIORITY", "--dry-run", "SOURCE_PRIORITY --dry-run", "DESTINATION_PRIORITY --dry-run"))
                .build();
    }

    @Override public @NotNull List<VexelArg<?>> getArguments() { return List.of(ACTION_ARG, WORLD_ARG, ARG3, FLAGS_ARG); }

    @Override
    public void execute(@NotNull CommandContext<CommandSourceStack> ctx, @NotNull CommandSender sender) {
        String actionStr = requireArg(ctx, ACTION_ARG);
        NamespacedKey worldKey = requireArg(ctx, WORLD_ARG);
        String arg3 = optionalArg(ctx, ARG3);
        String flagsInput = optionalArg(ctx, FLAGS_ARG);

        // Fix: If arg3 starts with "--", it was passed as a flag, so shift it
        if (arg3 != null && arg3.startsWith("--")) {
            flagsInput = (flagsInput == null) ? arg3 : arg3 + " " + flagsInput;
            arg3 = null;
        }

        String action = actionStr.toLowerCase(Locale.ROOT);
        VoyageAPI api = context.getWorldManager();

        if (!api.worldExists(worldKey) && api.getManagedWorld(worldKey).isEmpty() && Bukkit.getWorld(worldKey) == null) {
            TextUtils.send(sender, "<color:#e98df5>ℹ</color> <red>Dimension <white><world></white> does not exist.</red>", Placeholder.unparsed("world", worldKey.asString()));
            return;
        }

        if ("break".equals(action)) {
            String currentProfile = api.getWorldDataProfile(worldKey);
            if (currentProfile.startsWith("isolated:")) {
                TextUtils.send(sender, " ↪ <yellow>Data for <white><world></white> is already isolated.</yellow>", Placeholder.unparsed("world", worldKey.asString()));
                return;
            }

            boolean cloneData = "true".equalsIgnoreCase(arg3);

            FlagParser flags = new FlagParser(flagsInput);
            if (flags.hasFlag("dry-run")) {
                String targetLabel = context.getPlayerDataManager().getDefaultProfileId(worldKey);
                TextUtils.send(sender, "<dark_gray><st>          </st> <color:#e98df5>\ud83d\udcc4 Unlink Preview <white><current></white> \u2192 <white><target></white> \ud83d\udcc4</color> <st>          </st> </dark_gray>", Placeholder.unparsed("current", currentProfile), Placeholder.unparsed("target", targetLabel));
                TextUtils.send(sender, "  • <yellow>Isolating:</yellow> World <white><world></white> will be removed from <white><current></white>.", Placeholder.unparsed("world", worldKey.asString()), Placeholder.unparsed("current", currentProfile));
                TextUtils.send(sender, "  • <yellow>Mode:</yellow> <white><mode></white>", Placeholder.unparsed("mode", cloneData ? "Clone existing data into new isolated profile" : "Start fresh with empty profile"));
                String cmd = "/world profile-link break " + worldKey.asString() + " " + cloneData;
                TextUtils.send(sender, "  <click:run_command:'" + cmd + "'><hover:show_text:'Click to execute'><green>[Click to confirm]</green></hover></click>");
                return;
            }

            TextUtils.send(sender, " ↪ <yellow>Isolating data for <white><world></white>...</yellow>", Placeholder.unparsed("world", worldKey.asString()));

            api.breakWorldDataLink(worldKey, cloneData).thenRun(() -> {
                Bukkit.getScheduler().runTask(context.getPlugin(), () -> {
                    TextUtils.send(sender, " ↪ <green>Data link broken for <white><world></white>. <status></green>",
                            Placeholder.unparsed("world", worldKey.asString()), Placeholder.unparsed("status", cloneData ? "Data cloned." : "Started fresh."));
                    context.getWebhookService().notify("PROFILE_LINK", sender, "Broke data link for " + worldKey.asString() + (cloneData ? " (Cloned data)" : " (Started fresh)"));
                });
            }).exceptionally(ex -> {
                Bukkit.getScheduler().runTask(context.getPlugin(), () -> {
                    TextUtils.send(sender, "<red>Failed to break data link.</red>");
                });
                context.getPlugin().getLogger().log(java.util.logging.Level.SEVERE, "Data link break failed", ex);
                return null;
            });
            return;
        }

        if ("create".equals(action)) {
            String targetInput = arg3;
            if (targetInput == null) {
                String currentProfile = api.getWorldDataProfile(worldKey);
                TextUtils.send(sender, " ↪ <yellow>World <white><world></white> is currently on profile ID: <white><profile></white></yellow>",
                        Placeholder.unparsed("world", worldKey.asString()), Placeholder.unparsed("profile", currentProfile));
                return;
            }

            // Resolve whether targetInput is an existing world or a group name
            String resolvedProfileId;
            NamespacedKey targetWorldKey = parseWorldKey(targetInput);

            if (targetWorldKey != null && (api.worldExists(targetWorldKey) || api.getManagedWorld(targetWorldKey).isPresent() || Bukkit.getWorld(targetWorldKey) != null)) {
                if (targetWorldKey.equals(worldKey)) {
                    TextUtils.send(sender, "<red>Cannot link a world to itself.</red>");
                    return;
                }
                String targetWorldProfile = api.getWorldDataProfile(targetWorldKey);
                if (targetWorldProfile.startsWith("group:")) {
                    resolvedProfileId = targetWorldProfile.substring(6);
                } else {
                    // targetWorld is currently isolated; put both worlds into a shared group named after targetWorld
                    String newGroupName = sanitizeGroupName(targetWorldKey.getKey());
                    resolvedProfileId = newGroupName;
                    // Ensure the target world is added to the newly formed group first
                    api.setWorldDataProfile(targetWorldKey, newGroupName, VoyageAPI.DataLinkMergeStrategy.DESTINATION_PRIORITY);
                }
            } else {
                resolvedProfileId = targetInput.startsWith("group:") ? targetInput.substring(6) : targetInput;
            }

            if (!resolvedProfileId.equalsIgnoreCase("none")) {
                if (!resolvedProfileId.matches("^[a-zA-Z0-9_-]{1,32}$")) {
                    TextUtils.send(sender, "<red>Invalid profile ID. Must be 1-32 alphanumeric characters, dashes, or underscores.</red>");
                    return;
                }
            }

            FlagParser flags = new FlagParser(flagsInput);
            boolean isDryRun = flags.hasFlag("dry-run");
            VoyageAPI.DataLinkMergeStrategy strategy = VoyageAPI.DataLinkMergeStrategy.DESTINATION_PRIORITY;

            if (!flags.getPositionalArgs().isEmpty()) {
                try {
                    strategy = VoyageAPI.DataLinkMergeStrategy.valueOf(flags.getPositionalArgs().get(0).toUpperCase(Locale.ROOT));
                } catch (IllegalArgumentException e) {
                    TextUtils.send(sender, "<red>Invalid strategy: <strategy></red>", Placeholder.unparsed("strategy", flags.getPositionalArgs().get(0)));
                    return;
                }
            }

            String currentProfile = api.getWorldDataProfile(worldKey);
            String targetLabel = resolvedProfileId.equalsIgnoreCase("none") ? context.getPlayerDataManager().getDefaultProfileId(worldKey) : "group:" + resolvedProfileId;

            if (currentProfile.equals(targetLabel)) {
                if (resolvedProfileId.equalsIgnoreCase("none")) {
                    TextUtils.send(sender, " ↪ <yellow>Data for <white><world></white> is already isolated.</yellow>", Placeholder.unparsed("world", worldKey.asString()));
                } else {
                    TextUtils.send(sender, " ↪ <yellow>World <white><world></white> is already linked to profile <white><profile></white>.</yellow>", Placeholder.unparsed("world", worldKey.asString()), Placeholder.unparsed("profile", resolvedProfileId));
                }
                return;
            }

            if (isDryRun) {
                VoyageAPI.DataLinkPreview preview = api.previewWorldDataProfile(worldKey, resolvedProfileId, strategy);

                TextUtils.send(sender, "<dark_gray><st>          </st> <color:#e98df5>\ud83d\udcc4 Merge Preview <white><current></white> \u2192 <white><target></white> \ud83d\udcc4</color> <st>          </st> </dark_gray>", Placeholder.unparsed("current", currentProfile), Placeholder.unparsed("target", targetLabel));
                TextUtils.send(sender, "  • <green>Transferred:</green> <white><count></white> player(s) have data unique to <white><current></white> and will be added to <white><target></white>.", Placeholder.unparsed("count", String.valueOf(preview.newToTarget())), Placeholder.unparsed("current", currentProfile), Placeholder.unparsed("target", targetLabel));
                TextUtils.send(sender, "  • <yellow>Unaffected:</yellow> <white><count></white> player(s) exist only in <white><target></white> and remain untouched.", Placeholder.unparsed("count", String.valueOf(preview.targetOnly())), Placeholder.unparsed("target", targetLabel));
                TextUtils.send(sender, "  • <red>Conflicts:</red> <white><count></white> player(s) have data in BOTH profiles.", Placeholder.unparsed("count", String.valueOf(preview.conflicts())));

                if (preview.conflicts() > 0) {
                    if (strategy == VoyageAPI.DataLinkMergeStrategy.DESTINATION_PRIORITY) {
                        TextUtils.send(sender, "    <red>\u21b3 Because of DESTINATION_PRIORITY, their data in <white><current></white> will be PERMANENTLY LOST.</red>", Placeholder.unparsed("current", currentProfile));
                    } else {
                        TextUtils.send(sender, "    <red>\u21b3 Because of SOURCE_PRIORITY, their data in <white><target></white> will be PERMANENTLY LOST.</red>", Placeholder.unparsed("target", targetLabel));
                    }
                }

                String cmd = "/world profile-link create " + worldKey.asString() + " " + resolvedProfileId + " " + strategy.name();
                TextUtils.send(sender, "  <click:run_command:'" + cmd + "'><hover:show_text:'Click to execute'><green>[Click to confirm]</green></hover></click>");
                return;
            }

            TextUtils.send(sender, " ↪ <yellow>Linking data for <white><world></white>...</yellow>", Placeholder.unparsed("world", worldKey.asString()));

            api.setWorldDataProfile(worldKey, resolvedProfileId, strategy).thenRun(() -> {
                Bukkit.getScheduler().runTask(context.getPlugin(), () -> {
                    if ("none".equalsIgnoreCase(resolvedProfileId)) {
                        TextUtils.send(sender, " ↪ <green>Data for <white><world></white> is now isolated.</green>", Placeholder.unparsed("world", worldKey.asString()));
                        context.getWebhookService().notify("PROFILE_LINK", sender, "Isolated data for " + worldKey.asString());
                    } else {
                        TextUtils.send(sender, " ↪ <green>Data for <white><world></white> linked to profile <white><profile></white>.</green>", Placeholder.unparsed("world", worldKey.asString()), Placeholder.unparsed("profile", resolvedProfileId));
                        context.getWebhookService().notify("PROFILE_LINK", sender, "Linked data for " + worldKey.asString() + " to profile " + resolvedProfileId);
                    }
                });
            }).exceptionally(ex -> {
                Bukkit.getScheduler().runTask(context.getPlugin(), () -> {
                    Throwable cause = ex instanceof java.util.concurrent.CompletionException ? ex.getCause() : ex;
                    TextUtils.send(sender, "<red>Failed to link data: <error></red>", Placeholder.unparsed("error", cause.getMessage() != null ? cause.getMessage() : ex.getClass().getSimpleName()));
                });
                context.getPlugin().getLogger().log(java.util.logging.Level.SEVERE, "Data link failed", ex);
                return null;
            });
        } else {
            TextUtils.send(sender, "<red>Invalid action: <action>. Use 'create' or 'break'.</red>", Placeholder.unparsed("action", actionStr));
        }
    }

    private NamespacedKey parseWorldKey(String input) {
        if (input == null || input.isBlank()) return null;
        if (input.contains(":")) {
            return NamespacedKey.fromString(input.toLowerCase(Locale.ROOT));
        }
        NamespacedKey direct = NamespacedKey.fromString(input.toLowerCase(Locale.ROOT));
        if (direct != null && (context.getWorldManager().worldExists(direct) || Bukkit.getWorld(direct) != null)) {
            return direct;
        }
        NamespacedKey custom = NamespacedKey.fromString("custom:" + input.toLowerCase(Locale.ROOT));
        if (custom != null && (context.getWorldManager().worldExists(custom) || Bukkit.getWorld(custom) != null)) {
            return custom;
        }
        return direct;
    }

    private String sanitizeGroupName(String raw) {
        String clean = raw.replaceAll("[^a-zA-Z0-9_-]", "_");
        if (clean.length() > 32) clean = clean.substring(0, 32);
        return clean.isEmpty() ? "group" : clean;
    }
}