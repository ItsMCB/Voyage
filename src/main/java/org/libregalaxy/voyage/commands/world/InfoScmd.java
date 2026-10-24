package org.libregalaxy.voyage.commands.world;

import com.mojang.brigadier.arguments.StringArgumentType;
import com.mojang.brigadier.context.CommandContext;
import io.papermc.paper.command.brigadier.CommandSourceStack;
import me.itsmcb.vexelcore.bukkit.api.framework.FlagParser;
import me.itsmcb.vexelcore.bukkit.api.framework.TextUtils;
import me.itsmcb.vexelcore.bukkit.api.framework.VexelArg;
import me.itsmcb.vexelcore.bukkit.api.framework.VexelFlag;
import me.itsmcb.vexelcore.bukkit.api.framework.VexelFlags;
import me.itsmcb.vexelcore.bukkit.api.framework.VexelSenderSubCommand;
import net.kyori.adventure.text.minimessage.tag.resolver.Placeholder;
import org.bukkit.Bukkit;
import org.bukkit.GameRule;
import org.bukkit.NamespacedKey;
import org.bukkit.Registry;
import org.bukkit.World;
import org.bukkit.command.CommandSender;
import org.jetbrains.annotations.NotNull;
import org.libregalaxy.voyage.VoyageContext;
import org.libregalaxy.voyage.api.ManagedWorld;
import org.libregalaxy.voyage.api.VoyageAPI;
import org.libregalaxy.voyage.api.portal.PortalType;
import org.libregalaxy.voyage.core.VoyageWorldManager;

import java.io.File;
import java.io.IOException;
import java.nio.file.FileVisitResult;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.SimpleFileVisitor;
import java.nio.file.attribute.BasicFileAttributes;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Optional;

public class InfoScmd extends VexelSenderSubCommand<VoyageContext> {

    private final VexelFlags infoFlags;
    private final VexelArg<String> INPUT_ARG;

    public InfoScmd(@NotNull VoyageContext context) {
        super(context, "info", "Shows information about the current or specified world.", "voyage.info");

        this.infoFlags = VexelFlags.builder()
                .flag(VexelFlag.valued("gamerule", "Specific GameRule to query", ctx -> {
                    List<String> list = new ArrayList<>();
                    for (GameRule<?> rule : Registry.GAME_RULE) {
                        list.add(rule.getKey().value());
                    }
                    return list;
                }))
                .build();

        this.INPUT_ARG = VexelArg.builder("world_and_flags", StringArgumentType.greedyString(), String.class, "Target dimension and/or --gamerule flag")
                .optional()
                .suggestsContextual(ctx -> {
                    List<String> options = new ArrayList<>(context.getWorldManager().getAllWorldNames());
                    options.add("--gamerule");
                    return options;
                })
                .build();
    }

    @Override public @NotNull List<VexelArg<?>> getArguments() { return List.of(INPUT_ARG); }

    @Override
    public void execute(@NotNull CommandContext<CommandSourceStack> ctx, @NotNull CommandSender sender) {
        String input = optionalArg(ctx, INPUT_ARG);
        FlagParser flags = infoFlags.parse(input);

        NamespacedKey targetKey = null;
        String gameruleQuery = null;
        boolean showGamerules = false;

        if (flags.hasFlag("gamerule")) {
            showGamerules = true;
            gameruleQuery = flags.getValue("gamerule").orElse(null);
        }

        if (!flags.getPositionalArgs().isEmpty()) {
            String rawArg = flags.getPositionalArgs().getFirst();
            targetKey = NamespacedKey.fromString(rawArg);
            if (targetKey == null) {
                TextUtils.send(sender, "<color:#e98df5>ℹ</color> <red>Invalid dimension key format: <white><input></white>. Must follow namespace:key (e.g., custom:lobby).</red>",
                        Placeholder.unparsed("input", rawArg));
                return;
            }
        }

        NamespacedKey worldKey = targetKey != null ? targetKey : (sender instanceof org.bukkit.entity.Player p ? p.getWorld().getKey() : null);

        if (worldKey == null) {
            TextUtils.send(sender, "<red>You must specify a world when running from console.</red>");
            return;
        }

        World bukkitWorld = Bukkit.getWorld(worldKey);

        // --- GAMERULE DISPLAY LOGIC ---
        if (showGamerules) {
            if (bukkitWorld == null) {
                TextUtils.send(sender, "<color:#e98df5>ℹ</color> <red>World <white><world></white> must be loaded to view GameRules.</red>", Placeholder.unparsed("world", worldKey.asString()));
                return;
            }

            if (gameruleQuery != null) {
                String snakeCaseRule = gameruleQuery.replaceAll("([a-z])([A-Z]+)", "$1_$2").toLowerCase(Locale.ROOT);
                NamespacedKey ruleKey = NamespacedKey.fromString(snakeCaseRule);
                GameRule<?> registeredRule = ruleKey != null ? Registry.GAME_RULE.get(ruleKey) : null;

                if (registeredRule == null) {
                    TextUtils.send(sender, "<color:#e98df5>ℹ</color> <red>Unknown GameRule: <white><rule></white></red>", Placeholder.unparsed("rule", gameruleQuery));
                    return;
                }

                try {
                    String val = String.valueOf(bukkitWorld.getGameRuleValue(registeredRule));
                    TextUtils.send(sender, "<color:#e98df5>ℹ</color> <gray>GameRule <white><rule></white> in <white><world></white> is set to: <green><val></green></gray>",
                            Placeholder.unparsed("rule", registeredRule.getKey().value()),
                            Placeholder.unparsed("world", worldKey.asString()),
                            Placeholder.unparsed("val", val));
                } catch (IllegalArgumentException e) {
                    TextUtils.send(sender, "<color:#e98df5>ℹ</color> <red>GameRule <white><rule></white> is experimental or not active in this world.</red>", Placeholder.unparsed("rule", registeredRule.getKey().value()));
                }
            } else {
                TextUtils.send(sender, "<dark_gray><st>          </st> <color:#e98df5>\ud83d\udcc4 GameRules for <green><world></green> \ud83d\udcc4</color> <st>          </st> </dark_gray>", Placeholder.unparsed("world", worldKey.asString()));
                for (GameRule<?> rule : Registry.GAME_RULE) {
                    try {
                        String val = String.valueOf(bukkitWorld.getGameRuleValue(rule));
                        TextUtils.send(sender, " \u21aa <color:#e98df5><rule></color><dark_gray>:</dark_gray> <green><val></green>",
                                Placeholder.unparsed("rule", rule.getKey().value()),
                                Placeholder.unparsed("val", val));
                    } catch (IllegalArgumentException ignored) {
                        // Suppressed: rule does not apply to this dimension environment
                    }
                }
            }
            return;
        }

        // --- STANDARD INFO LOGIC ---
        VoyageAPI api = context.getWorldManager();
        VoyageWorldManager manager = context.getWorldManager();

        Optional<ManagedWorld> managedOpt = api.getManagedWorld(worldKey);

        String status = managedOpt.isPresent() ? "<green>Managed</green>" : "<gray>Unmanaged</gray>";
        if (bukkitWorld != null) {
            status += " <dark_gray>(<green>Online</green>)</dark_gray>";
        } else {
            if (managedOpt.isEmpty()) {
                TextUtils.send(sender, " \u21aa <red>World <white><world></white> is neither loaded nor managed by Voyage.</red>", Placeholder.unparsed("world", worldKey.asString()));
                return;
            }
            status += " <dark_gray>(<red>Offline</red>)</dark_gray>";
        }

        final File worldFolder = bukkitWorld != null
                ? bukkitWorld.getWorldFolder()
                : manager.resolveWorldFolder(worldKey);

        final String dataProfile = api.getWorldDataProfile(worldKey);
        final String borderSize = bukkitWorld != null ? String.valueOf(bukkitWorld.getWorldBorder().getSize()) : "Unknown (World Not Loaded)";
        final String chunkCount = bukkitWorld != null ? String.valueOf(bukkitWorld.getChunkCount()) : "Unknown (World Not Loaded)";
        final String entityCount = bukkitWorld != null ? String.valueOf(bukkitWorld.getEntityCount()) : "Unknown (World Not Loaded)";
        final String environment = managedOpt.map(mw -> mw.getEnvironment().name())
                .orElse(bukkitWorld != null ? bukkitWorld.getEnvironment().name() : "Unknown (World Not Loaded)");
        final String difficulty = bukkitWorld != null ? bukkitWorld.getDifficulty().name() : "Unknown (World Not Loaded)";
        final String worldKeyStr = worldKey.asString();

        final String resolvedPath;
        try {
            resolvedPath = worldFolder.getCanonicalPath();
        } catch (Exception e) {
            TextUtils.send(sender, "<red>Error resolving world path.</red>");
            return;
        }

        sendInfoAsync(sender, managedOpt, worldFolder, dataProfile, borderSize,
                chunkCount, entityCount, environment, difficulty, worldKeyStr, resolvedPath, status);
    }

    private void sendInfoAsync(@NotNull CommandSender sender,
                               @NotNull Optional<ManagedWorld> managedOpt,
                               @NotNull File worldFolder,
                               @NotNull String dataProfile,
                               @NotNull String borderSize,
                               @NotNull String chunkCount,
                               @NotNull String entityCount,
                               @NotNull String environment,
                               @NotNull String difficulty,
                               @NotNull String worldKeyStr,
                               @NotNull String resolvedPath,
                               @NotNull String finalStatus) {

        context.getPlugin().getServer().getScheduler().runTaskAsynchronously(context.getPlugin(), () -> {
            long sizeBytes = getFolderSize(worldFolder);
            String sizeFormatted = formatFolderSizeString(sizeBytes);

            context.getPlugin().getServer().getScheduler().runTask(context.getPlugin(), () -> {
                if (sender instanceof org.bukkit.entity.Player p && !p.isOnline()) return;

                TextUtils.send(sender, "<dark_gray><st>          </st> <color:#e98df5>\ud83d\udcc4 World Info of <green><world></green> \ud83d\udcc4</color> <st>          </st> </dark_gray>", Placeholder.unparsed("world", worldKeyStr));

                TextUtils.send(sender, " \u21aa <color:#e98df5>Namespace</color><dark_gray>:</dark_gray> <gray><hover:show_text:'<gray>Click to copy</gray>'><click:copy_to_clipboard:'" + worldKeyStr + "'>" + worldKeyStr + "</click></hover></gray>");

                TextUtils.send(sender, " \u21aa <color:#e98df5>Status</color><dark_gray>:</dark_gray> <status>", Placeholder.parsed("status", finalStatus));
                TextUtils.send(sender, " \u21aa <color:#e98df5>Environment</color><dark_gray>:</dark_gray> <gray><env></gray>", Placeholder.unparsed("env", environment));
                TextUtils.send(sender, " \u21aa <color:#e98df5>Difficulty</color><dark_gray>:</dark_gray> <gray><diff></gray>", Placeholder.unparsed("diff", difficulty));
                TextUtils.send(sender, " \u21aa <color:#e98df5>Chunk Count</color><dark_gray>:</dark_gray> <gray><chunks></gray>", Placeholder.unparsed("chunks", chunkCount));
                TextUtils.send(sender, " \u21aa <color:#e98df5>Entity Count</color><dark_gray>:</dark_gray> <gray><ents></gray>", Placeholder.unparsed("ents", entityCount));
                TextUtils.send(sender, " \u21aa <color:#e98df5>Data Profile</color><dark_gray>:</dark_gray> <gray><profile></gray>", Placeholder.unparsed("profile", dataProfile));
                TextUtils.send(sender, " \u21aa <color:#e98df5>Border Size</color><dark_gray>:</dark_gray> <gray><border> blocks</gray>", Placeholder.unparsed("border", borderSize));
                TextUtils.send(sender, " \u21aa <color:#e98df5>Disk</color><dark_gray>:</dark_gray>");
                TextUtils.send(sender, "   \u21aa <color:#e98df5>Size</color><dark_gray>:</dark_gray> <gray><size></gray>", Placeholder.unparsed("size", sizeFormatted));
                TextUtils.send(sender, "   \u21aa <color:#e98df5>Path</color><dark_gray>:</dark_gray> <gray><path></gray>", Placeholder.unparsed("path", resolvedPath));

                managedOpt.ifPresent(mw -> {
                    TextUtils.send(sender, " \u21aa <color:#e98df5>Auto Load</color><dark_gray>:</dark_gray> <gray><val></gray>", Placeholder.unparsed("val", String.valueOf(mw.isAutoLoad())));
                    TextUtils.send(sender, " \u21aa <color:#e98df5>Temporary</color><dark_gray>:</dark_gray> <gray><val></gray>", Placeholder.unparsed("val", String.valueOf(mw.isTemporary())));

                    TextUtils.send(sender, " \u21aa <color:#e98df5>Seed</color><dark_gray>:</dark_gray> <gray><hover:show_text:'<gray>Click to copy seed</gray>'><click:copy_to_clipboard:'" + mw.getSeed() + "'>" + mw.getSeed() + "</click></hover></gray>");

                    TextUtils.send(sender, " \u21aa <color:#e98df5>Generator</color><dark_gray>:</dark_gray> <gray><val></gray>", Placeholder.unparsed("val", mw.getGenerator().orElse("Default")));

                    TextUtils.send(sender, " \u21aa <color:#e98df5>Portal Links:</color>");
                    boolean hasLinks = false;
                    for (PortalType type : PortalType.values()) {
                        var linkOpt = mw.getPortalLink(type);
                        if (linkOpt.isPresent()) {
                            hasLinks = true;
                            TextUtils.send(sender, "   <dark_gray>▪</dark_gray> <white><type></white> ➝ <gray><tgt></gray>",
                                    Placeholder.unparsed("type", type.name()),
                                    Placeholder.unparsed("tgt", linkOpt.get().targetWorld().asString()));
                        }
                    }
                    if (!hasLinks) {
                        TextUtils.send(sender, "   <gray><italic>None configured</italic></gray>");
                    }
                });
            });
        });
    }

    public static String formatFolderSizeString(long sizeBytes) {
        if (sizeBytes < 1000) {
            return sizeBytes + " bytes";
        }

        final String[] units = {"kB", "MB", "GB", "TB", "PB", "EB"};
        int unitIndex = -1;
        double size = sizeBytes;

        while (size >= 1000 && unitIndex < units.length - 1) {
            size /= 1000.0;
            unitIndex++;
        }

        return String.format("%.1f %s", size, units[unitIndex]);
    }

    private long getFolderSize(@NotNull File folder) {
        if (!folder.exists()) return 0;
        final long[] size = {0};
        try {
            Files.walkFileTree(folder.toPath(), new SimpleFileVisitor<>() {
                @Override
                public FileVisitResult visitFile(Path file, BasicFileAttributes attrs) {
                    size[0] += attrs.size();
                    return FileVisitResult.CONTINUE;
                }
            });
        } catch (IOException e) {
            context.getPlugin().getLogger().warning("Failed to evaluate the disk space used by " + folder.getAbsolutePath());
        }
        return size[0];
    }
}