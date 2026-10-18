package org.libregalaxy.voyage.commands.world;

import com.mojang.brigadier.arguments.StringArgumentType;
import com.mojang.brigadier.context.CommandContext;
import io.papermc.paper.command.brigadier.CommandSourceStack;
import me.itsmcb.vexelcore.bukkit.api.framework.FlagParser;
import me.itsmcb.vexelcore.bukkit.api.framework.TextUtils;
import me.itsmcb.vexelcore.bukkit.api.framework.VexelArg;
import me.itsmcb.vexelcore.bukkit.api.framework.VexelSenderSubCommand;
import net.kyori.adventure.text.minimessage.tag.resolver.Placeholder;
import org.bukkit.Bukkit;
import org.bukkit.NamespacedKey;
import org.bukkit.World;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Player;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;
import org.libregalaxy.voyage.VoyageContext;
import org.libregalaxy.voyage.api.VoyageAPI;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.stream.Stream;

public class ImportScmd extends VexelSenderSubCommand<VoyageContext> {

    private static final String WORLDS_TO_IMPORT_DIR = "worlds-to-import";
    private final VexelArg<String> folderArg;
    private final VexelArg<String> flagsArg;

    public ImportScmd(@NotNull VoyageContext context) {
        super(context, "import", "Manually upgrade and import legacy dimensions into Paper 26.x format.", "voyage.admin");
        this.folderArg = VexelArg.builder("folder", StringArgumentType.string(), String.class, "Folder name in worlds-to-import/, or --all")
                .suggests(this::suggestFolders)
                .build();

        this.flagsArg = VexelArg.builder("flags", StringArgumentType.greedyString(), String.class, "Flags: --namespace <ns>, --key <key>, --generator <gen>, --generator-settings <settings>, --do-not-auto-link, --link-data <profile> <strategy>, --environment <env>, --delete-after-import")
                .optional()
                .build();
    }

    @Override public @NotNull List<VexelArg<?>> getArguments() { return List.of(folderArg, flagsArg); }

    @Override
    public void execute(@NotNull CommandContext<CommandSourceStack> ctx, @NotNull CommandSender sender) {
        String target = requireArg(ctx, folderArg);
        String flagsInput = optionalArg(ctx, flagsArg);

        Path importDir = Bukkit.getWorldContainer().toPath().resolve(WORLDS_TO_IMPORT_DIR);

        if (!Files.isDirectory(importDir)) {
            try {
                Files.createDirectories(importDir);
                TextUtils.send(sender, "<color:#e98df5>ℹ</color> <red>The <white>worlds-to-import/</white> directory did not exist. It has been created in the server root.</red>");
            } catch (Exception e) {
                TextUtils.send(sender, "<color:#e98df5>ℹ</color> <red>Failed to create worlds-to-import directory.</red>");
            }
            return;
        }

        FlagParser flags = new FlagParser(flagsInput);

        String namespace = "custom";
        if (flags.hasFlag("namespace")) {
            String ns = flags.getValue("namespace").orElse("");
            if (ns.matches("[a-z0-9_.-]+")) {
                namespace = ns;
            } else {
                TextUtils.send(sender, "<color:#e98df5>ℹ</color> <red>Invalid namespace format. Use lowercase letters, numbers, underscores, hyphens, or dots.</red>");
                return;
            }
        }

        String explicitKey = flags.getValue("key").or(() -> flags.getValue("name")).orElse(null);
        if (explicitKey != null) {
            explicitKey = explicitKey.toLowerCase(Locale.ROOT);
            if (!explicitKey.matches("[a-z0-9/._-]+")) {
                TextUtils.send(sender, "<color:#e98df5>ℹ</color> <red>Invalid key format for --key. Use lowercase letters, numbers, underscores, hyphens, slashes, or dots.</red>");
                return;
            }
            if ("--all".equalsIgnoreCase(target)) {
                TextUtils.send(sender, "<color:#e98df5>ℹ</color> <red>Cannot specify --key when importing all folders (--all).</red>");
                return;
            }
        }

        boolean autoLink = !flags.hasFlag("do-not-auto-link");
        boolean deleteAfterImport = flags.hasFlag("delete-after-import");

        World.Environment environment = null;
        if (flags.hasFlag("environment")) {
            String envStr = flags.getValue("environment").orElse("NORMAL").toUpperCase();
            try {
                environment = World.Environment.valueOf(envStr);
            } catch (IllegalArgumentException e) {
                TextUtils.send(sender, "<color:#e98df5>ℹ</color> <red>Invalid environment: <white><env></white>. Valid options: NORMAL, NETHER, THE_END</red>", Placeholder.unparsed("env", envStr));
                return;
            }
        }

        String generator = flags.getValue("generator").or(() -> flags.getValue("g")).orElse(null);
        String generatorSettings = flags.getValue("generator-settings").orElse(null);

        String dataProfile = flags.getValue("link-data").orElse(null);
        if (dataProfile != null && !dataProfile.equalsIgnoreCase("none")) {
            if (!dataProfile.matches("^[a-zA-Z0-9_-]{1,32}$")) {
                TextUtils.send(sender, "<red>Invalid profile ID. Must be 1-32 alphanumeric characters, dashes, or underscores.</red>");
                return;
            }
        }

        VoyageAPI.DataLinkMergeStrategy mergeStrategy = VoyageAPI.DataLinkMergeStrategy.DESTINATION_PRIORITY;

        List<String> linkDataValues = flags.getValues("link-data");
        if (linkDataValues.size() > 1) {
            try {
                mergeStrategy = VoyageAPI.DataLinkMergeStrategy.valueOf(linkDataValues.get(1).toUpperCase());
            } catch (IllegalArgumentException ignored) {}
        }

        final boolean finalAutoLink = autoLink;
        final String finalDataProfile = dataProfile;
        final VoyageAPI.DataLinkMergeStrategy finalMergeStrategy = mergeStrategy;
        final boolean finalDeleteAfterImport = deleteAfterImport;
        final World.Environment finalEnvironment = environment;
        final String finalGenerator = generator;
        final String finalGeneratorSettings = generatorSettings;

        List<Path> toImport = getFoldersToImport(importDir, target, sender);
        if (toImport.isEmpty()) {
            TextUtils.send(sender, "<color:#e98df5>ℹ</color> <yellow>No dimension folders found to import.</yellow>");
            return;
        }

        List<ImportTask> tasks = new ArrayList<>();
        for (Path legacyDir : toImport) {
            tasks.addAll(expandImportTargets(legacyDir, namespace, explicitKey, sender));
        }

        if (tasks.isEmpty()) {
            TextUtils.send(sender, "<color:#e98df5>ℹ</color> <yellow>No valid world directories found to import.</yellow>");
            return;
        }

        TextUtils.send(sender, "<color:#e98df5>ℹ</color> <yellow>Starting manual upgrade and import of <white><count></white> dimension(s)...</yellow>",
                Placeholder.unparsed("count", String.valueOf(tasks.size())));

        for (ImportTask task : tasks) {
            String displayKey = task.targetKey().asString();
            String folderName = task.sourceDir().getFileName().toString();

            context.getWorldManager().importWorld(
                    task.sourceDir(),
                    task.targetKey(),
                    finalAutoLink,
                    finalDataProfile,
                    finalMergeStrategy,
                    finalDeleteAfterImport,
                    finalEnvironment,
                    finalGenerator,
                    finalGeneratorSettings
            ).thenAccept(strategy -> {
                TextUtils.send(sender, "<color:#e98df5>ℹ</color> <green>Successfully imported <white><folder></white> as <white><key></white>!</green> <dark_gray>(Detected: <strategy>)</dark_gray>",
                        Placeholder.unparsed("folder", folderName),
                        Placeholder.unparsed("key", displayKey),
                        Placeholder.unparsed("strategy", strategy.getDisplayName()));
                if (sender instanceof Player p) {
                    context.getWebhookService().notify("WORLD_IMPORT", p, "Imported " + displayKey);
                }
            }).exceptionally(ex -> {
                Throwable cause = ex instanceof java.util.concurrent.CompletionException ? ex.getCause() : ex;
                context.getPlugin().getLogger().severe("Import failed for " + folderName + ": " + cause.getMessage());
                TextUtils.send(sender, "<color:#e98df5>ℹ</color> <red>Failed to import <white><folder></white>: <error></red>", Placeholder.unparsed("folder", folderName), Placeholder.unparsed("error", cause.getMessage()));
                return null;
            });
        }
    }

    private record ImportTask(@NotNull Path sourceDir, @NotNull NamespacedKey targetKey) {}

    private List<ImportTask> expandImportTargets(Path sourceDir, String namespace, @Nullable String explicitKey, CommandSender sender) {
        List<ImportTask> tasks = new ArrayList<>();

        // If sourceDir is a complete standalone world save (contains level.dat or region files directly),
        // do NOT descend into internal dimensions/minecraft/overworld. Treat sourceDir as the world save root.
        if (Files.isRegularFile(sourceDir.resolve("level.dat")) || Files.isDirectory(sourceDir.resolve("region"))) {
            String keyName = (explicitKey != null) ? explicitKey : sourceDir.getFileName().toString();
            NamespacedKey targetKey = resolveNamespacedKey(namespace, keyName);
            if (targetKey != null) {
                tasks.add(new ImportTask(sourceDir, targetKey));
            }
            return tasks;
        }

        Path dimsDir = sourceDir.resolve("dimensions");
        if (Files.isDirectory(dimsDir)) {
            try (Stream<Path> nsStream = Files.list(dimsDir)) {
                nsStream.filter(Files::isDirectory).forEach(nsDir -> {
                    try (Stream<Path> keyStream = Files.list(nsDir)) {
                        keyStream.filter(Files::isDirectory).forEach(keyDir -> {
                            String ns = nsDir.getFileName().toString();
                            String key = keyDir.getFileName().toString();
                            NamespacedKey targetKey = resolveNamespacedKey(ns, key);
                            if (targetKey != null) {
                                tasks.add(new ImportTask(keyDir, targetKey));
                            }
                        });
                    } catch (Exception ignored) {}
                });
            } catch (Exception ignored) {}
            if (!tasks.isEmpty()) return tasks;
        }

        try (Stream<Path> stream = Files.list(sourceDir)) {
            List<Path> subDirs = stream.filter(Files::isDirectory).toList();
            if (subDirs.size() > 1 && subDirs.stream().allMatch(d -> Files.exists(d.resolve("region")) || Files.exists(d.resolve("level.dat")))) {
                for (Path subDir : subDirs) {
                    String keyName = subDir.getFileName().toString();
                    NamespacedKey targetKey = resolveNamespacedKey(namespace, keyName);
                    if (targetKey != null) {
                        tasks.add(new ImportTask(subDir, targetKey));
                    }
                }
                return tasks;
            }
        } catch (Exception ignored) {}

        String keyName = (explicitKey != null) ? explicitKey : sourceDir.getFileName().toString();
        NamespacedKey targetKey = resolveNamespacedKey(namespace, keyName);
        if (targetKey != null) {
            tasks.add(new ImportTask(sourceDir, targetKey));
        } else {
            TextUtils.send(sender, "<color:#e98df5>ℹ</color> <red>Failed to derive a valid world key from folder name: <white><name></white></red>",
                    Placeholder.unparsed("name", keyName));
        }
        return tasks;
    }

    static @Nullable NamespacedKey resolveNamespacedKey(@Nullable String namespace, @Nullable String rawKey) {
        if (rawKey == null || rawKey.isBlank()) {
            return null;
        }

        String sanitizedKey = rawKey.toLowerCase(Locale.ROOT).replaceAll("[^a-z0-9/._-]", "_");
        sanitizedKey = sanitizedKey.replaceAll("_+", "_").replaceAll("^_+|_+$", "");
        if (sanitizedKey.isEmpty()) {
            sanitizedKey = "world";
        }

        String sanitizedNamespace = (namespace != null && !namespace.isBlank())
                ? namespace.toLowerCase(Locale.ROOT).replaceAll("[^a-z0-9._-]", "_")
                : "custom";
        sanitizedNamespace = sanitizedNamespace.replaceAll("_+", "_").replaceAll("^_+|_+$", "");
        if (sanitizedNamespace.isEmpty()) {
            sanitizedNamespace = "custom";
        }

        return NamespacedKey.fromString(sanitizedNamespace + ":" + sanitizedKey);
    }

    private List<String> suggestFolders() {
        List<String> options = new ArrayList<>();
        options.add("--all");
        Path importDir = Bukkit.getWorldContainer().toPath().resolve(WORLDS_TO_IMPORT_DIR);
        if (Files.isDirectory(importDir)) {
            try (var stream = Files.list(importDir)) {
                stream.filter(Files::isDirectory)
                        .map(p -> p.getFileName().toString())
                        .forEach(options::add);
            } catch (Exception ignored) {}
        }
        return options;
    }

    private List<Path> getFoldersToImport(Path importDir, String target, CommandSender sender) {
        List<Path> toImport = new ArrayList<>();
        if ("--all".equalsIgnoreCase(target)) {
            try (var stream = Files.list(importDir)) {
                stream.filter(Files::isDirectory).forEach(toImport::add);
            } catch (Exception e) {
                context.getPlugin().getLogger().severe("Failed to list worlds-to-import directory.");
            }
        } else {
            Path specificFolder = importDir.resolve(target).normalize();
            try {
                if (!specificFolder.toRealPath().startsWith(importDir.toRealPath())) {
                    TextUtils.send(sender, "<color:#e98df5>ℹ</color> <red>Invalid folder path (directory escape or symlink detected).</red>");
                    return toImport;
                }
            } catch (IOException e) {
                TextUtils.send(sender, "<red>Failed to resolve path: " + e.getMessage() + "</red>");
                return toImport;
            }
            if (!Files.isDirectory(specificFolder)) {
                TextUtils.send(sender, "<color:#e98df5>ℹ</color> <red>Folder <white><folder></white> not found inside worlds-to-import/.</red>", Placeholder.unparsed("folder", target));
                return toImport;
            }
            toImport.add(specificFolder);
        }
        return toImport;
    }
}