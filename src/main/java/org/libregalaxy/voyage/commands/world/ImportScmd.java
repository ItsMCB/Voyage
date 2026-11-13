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
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.event.ClickEvent;
import net.kyori.adventure.text.event.HoverEvent;
import net.kyori.adventure.text.format.NamedTextColor;
import net.kyori.adventure.text.format.TextDecoration;
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
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.stream.Stream;

public class ImportScmd extends VexelSenderSubCommand<VoyageContext> {

    private static final String IMPORT_DIR = "import";
    private static final String STAGING_DIR = "staging";
    private final VexelArg<String> folderArg;
    private final VexelFlags importFlags;
    private final VexelArg<String> flagsArg;

    public record ImportSource(@NotNull Path dir, @Nullable Path originalZip, @NotNull String logicalName) {}

    public ImportScmd(@NotNull VoyageContext context) {
        super(context, "import", "Manually upgrade and import legacy dimensions into Paper 26.x format.", "voyage.admin");
        this.folderArg = VexelArg.builder("folder", StringArgumentType.string(), String.class, "Folder or .zip in plugins/Voyage/import/, 'list', or --all")
                .suggests(this::suggestFolders)
                .build();

        this.importFlags = VexelFlags.builder()
                .flag(VexelFlag.valued("namespace", "Target dimension namespace (default: custom)"))
                .flag(VexelFlag.valued("key", "Explicit dimension key").alias("name"))
                .flag(VexelFlag.valued("generator", "Custom chunk generator plugin").alias("g"))
                .flag(VexelFlag.valued("generator-settings", "Generator settings JSON or string"))
                .flag(VexelFlag.booleanFlag("do-not-auto-link", "Disable automatic portal linking between dimensions"))
                .flag(VexelFlag.multiValued("link-data", "Link player data profile <profile> [strategy]", 2))
                .flag(VexelFlag.valued("environment", "Dimension environment", List.of("NORMAL", "NETHER", "THE_END")))
                .flag(VexelFlag.booleanFlag("delete-after-import", "Delete source folder from plugins/Voyage/import/ upon success"))
                .build();

        this.flagsArg = importFlags.asArg("flags",
                "Flags: --namespace <ns>, --key <key>, --generator <gen>, --generator-settings <settings>, --do-not-auto-link, --link-data <profile> [strategy], --environment <env>, --delete-after-import");
    }

    @Override public @NotNull List<VexelArg<?>> getArguments() { return List.of(folderArg, flagsArg); }

    private Path getImportDir() {
        return context.getPlugin().getDataFolder().toPath().resolve(IMPORT_DIR);
    }

    private Path getStagingDir() {
        return context.getPlugin().getDataFolder().toPath().resolve(STAGING_DIR);
    }

    @Override
    public void execute(@NotNull CommandContext<CommandSourceStack> ctx, @NotNull CommandSender sender) {
        String target = requireArg(ctx, folderArg);
        String flagsInput = optionalArg(ctx, flagsArg);

        Path importDir = getImportDir();

        if (!Files.isDirectory(importDir)) {
            try {
                Files.createDirectories(importDir);
                TextUtils.send(sender, "<color:#e98df5>ℹ</color> <red>The <white>plugins/Voyage/import/</white> directory did not exist. It has been created.</red>");
            } catch (Exception e) {
                TextUtils.send(sender, "<color:#e98df5>ℹ</color> <red>Failed to create plugins/Voyage/import/ directory.</red>");
            }
            return;
        }

        if ("list".equalsIgnoreCase(target)) {
            handleListAsync(sender, importDir);
            return;
        }

        FlagParser flags = importFlags.parse(flagsInput);

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

        String explicitKey = flags.getValue("key").orElse(null);
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
            String envStr = flags.getValue("environment").orElse("NORMAL").toUpperCase(Locale.ROOT);
            try {
                environment = World.Environment.valueOf(envStr);
            } catch (IllegalArgumentException e) {
                TextUtils.send(sender, "<color:#e98df5>ℹ</color> <red>Invalid environment: <white><env></white>. Valid options: NORMAL, NETHER, THE_END</red>", Placeholder.unparsed("env", envStr));
                return;
            }
        }

        String generator = flags.getValue("generator").orElse(null);
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
                mergeStrategy = VoyageAPI.DataLinkMergeStrategy.valueOf(linkDataValues.get(1).toUpperCase(Locale.ROOT));
            } catch (IllegalArgumentException ignored) {}
        }

        final String finalNamespace = namespace;
        final String finalExplicitKey = explicitKey;
        final boolean finalAutoLink = autoLink;
        final String finalDataProfile = dataProfile;
        final VoyageAPI.DataLinkMergeStrategy finalMergeStrategy = mergeStrategy;
        final boolean finalDeleteAfterImport = deleteAfterImport;
        final World.Environment finalEnvironment = environment;
        final String finalGenerator = generator;
        final String finalGeneratorSettings = generatorSettings;

        // Perform zip extraction and directory scanning off the main thread
        Bukkit.getScheduler().runTaskAsynchronously(context.getPlugin(), () -> {
            List<ImportSource> toImport = getFoldersToImport(importDir, target, sender);
            if (toImport.isEmpty()) {
                Bukkit.getScheduler().runTask(context.getPlugin(), () ->
                        TextUtils.send(sender, "<color:#e98df5>ℹ</color> <yellow>No dimension folders or archives found to import.</yellow>"));
                return;
            }

            int totalTasks = 0;
            for (ImportSource source : toImport) {
                totalTasks += expandImportTargets(source.dir(), source.logicalName(), finalNamespace, finalExplicitKey, sender).size();
            }

            if (totalTasks == 0) {
                for (ImportSource source : toImport) {
                    if (source.originalZip() != null) {
                        context.getWorldManager().deleteDirectory(source.dir().toFile());
                    }
                }
                Bukkit.getScheduler().runTask(context.getPlugin(), () ->
                        TextUtils.send(sender, "<color:#e98df5>ℹ</color> <yellow>No valid world directories found to import.</yellow>"));
                return;
            }

            final int finalTotalTasks = totalTasks;
            Bukkit.getScheduler().runTask(context.getPlugin(), () ->
                    TextUtils.send(sender, "<color:#e98df5>ℹ</color> <yellow>Starting manual upgrade and import of <white><count></white> dimension(s)...</yellow>",
                            Placeholder.unparsed("count", String.valueOf(finalTotalTasks))));

            CompletableFuture<Void> sourceChain = CompletableFuture.completedFuture(null);

            for (ImportSource source : toImport) {
                Path legacyDir = source.dir();
                List<ImportTask> tasks = expandImportTargets(legacyDir, source.logicalName(), finalNamespace, finalExplicitKey, sender);
                if (tasks.isEmpty()) {
                    if (source.originalZip() != null) {
                        context.getWorldManager().deleteDirectory(legacyDir.toFile());
                    }
                    continue;
                }

                sourceChain = sourceChain.thenCompose(v -> {
                    CompletableFuture<Void> taskChain = CompletableFuture.completedFuture(null);
                    AtomicBoolean allSucceeded = new AtomicBoolean(true);

                    for (ImportTask task : tasks) {
                        String displayKey = task.targetKey().asString();
                        String folderName = task.displayFolder();

                        taskChain = taskChain.thenCompose(t -> context.getWorldManager().importWorld(
                                task.sourceDir(),
                                task.targetKey(),
                                finalAutoLink,
                                finalDataProfile,
                                finalMergeStrategy,
                                false, // We manage cleanup of the parent legacyDir ourselves
                                finalEnvironment,
                                finalGenerator,
                                finalGeneratorSettings
                        ).thenAccept(strategy -> {
                            Bukkit.getScheduler().runTask(context.getPlugin(), () -> {
                                TextUtils.send(sender, "<color:#e98df5>ℹ</color> <green>Successfully imported <white><folder></white> as <white><key></white>!</green> <dark_gray>(Detected: <strategy>)</dark_gray>",
                                        Placeholder.unparsed("folder", folderName),
                                        Placeholder.unparsed("key", displayKey),
                                        Placeholder.unparsed("strategy", strategy.getDisplayName()));
                                if (sender instanceof Player p) {
                                    context.getWebhookService().notify("WORLD_IMPORT", p, "Imported " + displayKey);
                                }
                            });
                        }).exceptionally(ex -> {
                            allSucceeded.set(false);
                            Throwable cause = ex instanceof java.util.concurrent.CompletionException ? ex.getCause() : ex;
                            context.getPlugin().getLogger().severe("Import failed for " + folderName + ": " + cause.getMessage());
                            Bukkit.getScheduler().runTask(context.getPlugin(), () ->
                                    TextUtils.send(sender, "<color:#e98df5>ℹ</color> <red>Failed to import <white><folder></white>: <error></red>",
                                            Placeholder.unparsed("folder", folderName), Placeholder.unparsed("error", cause.getMessage())));
                            return null;
                        }));
                    }

                    return taskChain.thenRun(() -> {
                        if (allSucceeded.get()) {
                            if (source.originalZip() != null) {
                                context.getWorldManager().deleteDirectory(legacyDir.toFile());
                                if (finalDeleteAfterImport) {
                                    try { Files.deleteIfExists(source.originalZip()); } catch (IOException ignored) {}
                                } else {
                                    context.getWorldManager().getFileService().markAsImported(source.originalZip());
                                }
                            } else if (finalDeleteAfterImport) {
                                context.getWorldManager().deleteDirectory(legacyDir.toFile());
                            } else {
                                context.getWorldManager().getFileService().markAsImported(legacyDir);
                            }
                        } else if (source.originalZip() != null) {
                            // On failure, delete temporary staging folder but keep original .zip untouched
                            context.getWorldManager().deleteDirectory(legacyDir.toFile());
                        }
                    });
                });
            }
        });
    }

    private record EntryInfo(String name, boolean isZip, String sizeStr) {}

    private void handleListAsync(@NotNull CommandSender sender, @NotNull Path importDir) {
        Bukkit.getScheduler().runTaskAsynchronously(context.getPlugin(), () -> {
            List<Path> entries = new ArrayList<>();
            try (var stream = Files.list(importDir)) {
                for (Path p : stream.sorted().toList()) {
                    String name = p.getFileName().toString();
                    if (name.endsWith(".imported") || name.equalsIgnoreCase("README.txt")) continue;
                    if (Files.isDirectory(p) || (Files.isRegularFile(p) && name.endsWith(".zip"))) {
                        entries.add(p);
                    }
                }
            } catch (IOException e) {
                Bukkit.getScheduler().runTask(context.getPlugin(), () ->
                        TextUtils.send(sender, "<color:#e98df5>ℹ</color> <red>Failed to read plugins/Voyage/import/ directory.</red>"));
                return;
            }

            if (entries.isEmpty()) {
                Bukkit.getScheduler().runTask(context.getPlugin(), () ->
                        TextUtils.send(sender, "<color:#e98df5>ℹ</color> <yellow>No pending worlds or archives in plugins/Voyage/import/.</yellow>"));
                return;
            }

            List<EntryInfo> rendered = new ArrayList<>(entries.size());
            for (Path p : entries) {
                String name = p.getFileName().toString();
                boolean isZip = Files.isRegularFile(p) && name.endsWith(".zip");
                String sizeStr;
                try {
                    long sizeBytes;
                    if (isZip) {
                        sizeBytes = Files.size(p);
                    } else {
                        try (var walk = Files.walk(p)) {
                            sizeBytes = walk.filter(Files::isRegularFile).mapToLong(f -> {
                                try { return Files.size(f); } catch (Exception ignored) { return 0L; }
                            }).sum();
                        }
                    }
                    sizeStr = InfoScmd.formatFolderSizeString(sizeBytes);
                } catch (Exception ignored) {
                    sizeStr = "unknown";
                }
                rendered.add(new EntryInfo(name, isZip, sizeStr));
            }

            Bukkit.getScheduler().runTask(context.getPlugin(), () -> {
                if (sender instanceof Player p && !p.isOnline()) return;

                TextUtils.send(sender, "<dark_gray><st>          </st> <color:#e98df5>Worlds Ready for Import (<count>)</color> <st>          </st></dark_gray>",
                        Placeholder.unparsed("count", String.valueOf(rendered.size())));

                for (EntryInfo info : rendered) {
                    var builder = Component.text()
                            .append(Component.text("  • ", NamedTextColor.DARK_GRAY))
                            .append(Component.text(info.name(), NamedTextColor.WHITE))
                            .append(Component.text(" [" + info.sizeStr() + (info.isZip() ? ", archive" : ", folder") + "] ", NamedTextColor.GRAY))
                            .append(Component.text("[▶ Import]", NamedTextColor.AQUA, TextDecoration.BOLD, TextDecoration.UNDERLINED)
                                    .clickEvent(ClickEvent.runCommand("/world import \"" + info.name() + "\""))
                                    .hoverEvent(HoverEvent.showText(Component.text("Click to import " + info.name(), NamedTextColor.GRAY))));

                    sender.sendMessage(builder.build());
                }
            });
        });
    }

    private record ImportTask(@NotNull Path sourceDir, @NotNull NamespacedKey targetKey, @NotNull String displayFolder) {}

    private List<ImportTask> expandImportTargets(Path sourceDir, String logicalName, String namespace, @Nullable String explicitKey, CommandSender sender) {
        List<ImportTask> tasks = new ArrayList<>();

        if (Files.isRegularFile(sourceDir.resolve("level.dat")) || Files.isDirectory(sourceDir.resolve("region"))) {
            String keyName = (explicitKey != null) ? explicitKey : logicalName;
            NamespacedKey targetKey = resolveNamespacedKey(namespace, keyName);
            if (targetKey != null) {
                tasks.add(new ImportTask(sourceDir, targetKey, logicalName));
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
                                tasks.add(new ImportTask(keyDir, targetKey, key));
                            }
                        });
                    } catch (Exception ignored) {}
                });
            } catch (Exception ignored) {}
            if (!tasks.isEmpty()) return tasks;
        }

        try (Stream<Path> stream = Files.list(sourceDir)) {
            List<Path> subDirs = stream.filter(Files::isDirectory).toList();
            if (subDirs.size() == 1) {
                Path singleSub = subDirs.getFirst();
                if (Files.isRegularFile(singleSub.resolve("level.dat")) || Files.isDirectory(singleSub.resolve("region"))) {
                    String keyName = (explicitKey != null) ? explicitKey : logicalName;
                    NamespacedKey targetKey = resolveNamespacedKey(namespace, keyName);
                    if (targetKey != null) {
                        tasks.add(new ImportTask(singleSub, targetKey, logicalName));
                    }
                    return tasks;
                }
            } else if (subDirs.size() > 1 && subDirs.stream().allMatch(d -> Files.exists(d.resolve("region")) || Files.exists(d.resolve("level.dat")))) {
                for (Path subDir : subDirs) {
                    String keyName = subDir.getFileName().toString();
                    NamespacedKey targetKey = resolveNamespacedKey(namespace, keyName);
                    if (targetKey != null) {
                        tasks.add(new ImportTask(subDir, targetKey, keyName));
                    }
                }
                return tasks;
            }
        } catch (Exception ignored) {}

        String keyName = (explicitKey != null) ? explicitKey : logicalName;
        NamespacedKey targetKey = resolveNamespacedKey(namespace, keyName);
        if (targetKey != null) {
            tasks.add(new ImportTask(sourceDir, targetKey, logicalName));
        } else {
            Bukkit.getScheduler().runTask(context.getPlugin(), () ->
                    TextUtils.send(sender, "<color:#e98df5>ℹ</color> <red>Failed to derive a valid world key from folder name: <white><name></white></red>",
                            Placeholder.unparsed("name", keyName)));
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
        options.add("list");
        options.add("--all");
        Path importDir = getImportDir();
        if (Files.isDirectory(importDir)) {
            try (var stream = Files.list(importDir)) {
                stream.forEach(p -> {
                    String name = p.getFileName().toString();
                    if (!name.endsWith(".imported")) {
                        if (Files.isDirectory(p)) {
                            options.add(name);
                        } else if (Files.isRegularFile(p) && name.endsWith(".zip")) {
                            options.add(name);
                        }
                    }
                });
            } catch (Exception ignored) {}
        }
        return options;
    }

    private List<ImportSource> getFoldersToImport(Path importDir, String target, CommandSender sender) {
        List<ImportSource> toImport = new ArrayList<>();
        Path canonicalImportDir = importDir.toAbsolutePath().normalize();
        Path stagingDir = getStagingDir();

        if ("--all".equalsIgnoreCase(target)) {
            try (var stream = Files.list(canonicalImportDir)) {
                for (Path p : stream.toList()) {
                    String name = p.getFileName().toString();
                    if (name.endsWith(".imported")) continue;

                    if (Files.isDirectory(p)) {
                        toImport.add(new ImportSource(p, null, name));
                    } else if (Files.isRegularFile(p) && name.endsWith(".zip")) {
                        String logicalName = name.substring(0, name.length() - 4);
                        Path extracted = extractZipArchiveSafely(p, stagingDir, sender);
                        if (extracted != null) toImport.add(new ImportSource(extracted, p, logicalName));
                    }
                }
            } catch (Exception e) {
                context.getPlugin().getLogger().severe("Failed to list plugins/Voyage/import/ directory.");
            }
        } else {
            Path targetPath = canonicalImportDir.resolve(target).normalize();
            try {
                if (!targetPath.startsWith(canonicalImportDir)) {
                    Bukkit.getScheduler().runTask(context.getPlugin(), () ->
                            TextUtils.send(sender, "<color:#e98df5>ℹ</color> <red>Invalid folder path (directory escape detected).</red>"));
                    return toImport;
                }
            } catch (Exception e) {
                Bukkit.getScheduler().runTask(context.getPlugin(), () ->
                        TextUtils.send(sender, "<red>Failed to resolve path: " + e.getMessage() + "</red>"));
                return toImport;
            }

            String targetName = targetPath.getFileName().toString();
            if (Files.isRegularFile(targetPath) && targetName.endsWith(".zip")) {
                String logicalName = targetName.substring(0, targetName.length() - 4);
                Path extracted = extractZipArchiveSafely(targetPath, stagingDir, sender);
                if (extracted != null) toImport.add(new ImportSource(extracted, targetPath, logicalName));
            } else if (Files.isDirectory(targetPath)) {
                toImport.add(new ImportSource(targetPath, null, targetName));
            } else {
                Bukkit.getScheduler().runTask(context.getPlugin(), () ->
                        TextUtils.send(sender, "<color:#e98df5>ℹ</color> <red>Folder or .zip <white><target></white> not found inside plugins/Voyage/import/.</red>", Placeholder.unparsed("target", target)));
            }
        }
        return toImport;
    }

    private @Nullable Path extractZipArchiveSafely(@NotNull Path zipFile, @NotNull Path stagingDir, @NotNull CommandSender sender) {
        String zipName = zipFile.getFileName().toString();
        String folderName = zipName.substring(0, zipName.length() - 4);

        Path canonicalStagingDir = stagingDir.toAbsolutePath().normalize();
        // Extract into a unique staging directory inside plugins/Voyage/staging/ so it never overwrites or deletes an existing folder of the same name
        Path targetDir = canonicalStagingDir.resolve(folderName + "-" + System.nanoTime()).normalize();

        try {
            if (!targetDir.startsWith(canonicalStagingDir)) {
                throw new SecurityException("Invalid staging path for archive: " + zipName);
            }
            Files.createDirectories(canonicalStagingDir);
            context.getWorldManager().getFileService().extractZipArchive(zipFile, targetDir);
            return targetDir;
        } catch (Exception e) {
            context.getWorldManager().deleteDirectory(targetDir.toFile());
            Bukkit.getScheduler().runTask(context.getPlugin(), () ->
                    TextUtils.send(sender, "<color:#e98df5>ℹ</color> <red>Failed to extract archive <white><file></white>: <error></red>",
                            Placeholder.unparsed("file", zipName), Placeholder.unparsed("error", e.getMessage())));
            return null;
        }
    }
}