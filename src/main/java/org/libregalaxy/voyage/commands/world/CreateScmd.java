package org.libregalaxy.voyage.commands.world;

import com.mojang.brigadier.arguments.StringArgumentType;
import com.mojang.brigadier.context.CommandContext;
import io.papermc.paper.command.brigadier.CommandSourceStack;
import io.papermc.paper.command.brigadier.argument.ArgumentTypes;
import me.itsmcb.vexelcore.bukkit.api.framework.FlagParser;
import me.itsmcb.vexelcore.bukkit.api.framework.TextUtils;
import me.itsmcb.vexelcore.bukkit.api.framework.VexelArg;
import me.itsmcb.vexelcore.bukkit.api.framework.VexelPlayerSubCommand;
import net.kyori.adventure.text.minimessage.tag.resolver.Placeholder;
import org.bukkit.Difficulty;
import org.bukkit.GameRule;
import org.bukkit.NamespacedKey;
import org.bukkit.Registry;
import org.bukkit.World;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.entity.Player;
import org.libregalaxy.voyage.VoyageContext;
import org.libregalaxy.voyage.api.VoyageAPI;
import org.libregalaxy.voyage.api.builder.WorldBuilder;
import org.jetbrains.annotations.NotNull;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;

public class CreateScmd extends VexelPlayerSubCommand<VoyageContext> {

    private final VexelArg<NamespacedKey> KEY_ARG;
    private final VexelArg<String> FLAGS_ARG;

    public CreateScmd(@NotNull VoyageContext context) {
        super(context, "create", "Generates a new dimension.", "voyage.create");
        this.KEY_ARG = VexelArg.builder("namespacedkey", ArgumentTypes.namespacedKey(), NamespacedKey.class, "Identifier for the new world (e.g., \"custom:lobby\")")
                .build();

        this.FLAGS_ARG = VexelArg.builder("flags", StringArgumentType.greedyString(), String.class,
                        "Optional flags: --preset <name>, --environment <env>, --seed <seed>, --difficulty <diff>, --no-structures, --spawn <x> <y> <z>, --gamerule <name> <value>")
                .optional()
                .suggestsContextual(ctx -> {
                    List<String> options = new ArrayList<>();
                    options.add("--preset void");
                    options.add("--preset flat");
                    options.add("--preset amplified");
                    ConfigurationSection presets = context.getPlugin().getConfig().getConfigurationSection("presets");
                    if (presets != null) {
                        for (String key : presets.getKeys(false)) {
                            String opt = "--preset " + key;
                            if (!options.contains(opt)) options.add(opt);
                        }
                    }
                    options.add("--environment NORMAL");
                    options.add("--environment NETHER");
                    options.add("--environment THE_END");
                    options.add("--seed");
                    options.add("--difficulty PEACEFUL");
                    options.add("--difficulty EASY");
                    options.add("--difficulty NORMAL");
                    options.add("--difficulty HARD");
                    options.add("--no-structures");
                    options.add("--spawn");
                    options.add("--gamerule");
                    return options;
                })
                .build();
    }

    @Override public @NotNull List<VexelArg<?>> getArguments() { return List.of(KEY_ARG, FLAGS_ARG); }

    @Override
    public void execute(@NotNull CommandContext<CommandSourceStack> ctx, @NotNull Player player) {
        NamespacedKey namespacedKey = requireArg(ctx, KEY_ARG);
        String flagsInput = optionalArg(ctx, FLAGS_ARG);
        VoyageAPI api = context.getWorldManager();

        FlagParser flags = new FlagParser(flagsInput);

        World.Environment env = null;
        String seedStr = null;
        Difficulty difficulty = null;
        boolean generateStructures = true;
        int[] spawn = null;
        Map<String, String> gameruleStrings = new LinkedHashMap<>();

        Optional<String> presetOpt = flags.getValue("preset").or(() -> flags.getValue("p"));
        String presetName = presetOpt.orElse(null);

        Optional<String> envOpt = flags.getValue("environment").or(() -> flags.getValue("e"));
        if (envOpt.isPresent()) {
            String envStr = envOpt.get().toUpperCase(Locale.ROOT);
            try {
                env = World.Environment.valueOf(envStr);
            } catch (IllegalArgumentException e) {
                TextUtils.send(player, "<color:#e98df5>ℹ</color> <red>Invalid environment: <white><env></white>. Valid options: NORMAL, NETHER, THE_END, CUSTOM</red>", Placeholder.unparsed("env", envStr));
                return;
            }
        }

        Optional<String> seedOpt = flags.getValue("seed").or(() -> flags.getValue("s"));
        if (seedOpt.isPresent()) {
            seedStr = seedOpt.get();
        }

        Optional<String> diffOpt = flags.getValue("difficulty").or(() -> flags.getValue("d"));
        if (diffOpt.isPresent()) {
            String diffStr = diffOpt.get().toUpperCase(Locale.ROOT);
            try {
                difficulty = Difficulty.valueOf(diffStr);
            } catch (IllegalArgumentException e) {
                TextUtils.send(player, "<color:#e98df5>ℹ</color> <red>Invalid difficulty: <white><diff></white>. Valid options: PEACEFUL, EASY, NORMAL, HARD</red>", Placeholder.unparsed("diff", diffStr));
                return;
            }
        }

        if (flags.hasFlag("no-structures")) {
            generateStructures = false;
        }

        List<String> spawnValues = flags.getValues("spawn");
        if (spawnValues.size() >= 3) {
            try {
                int sx = Integer.parseInt(spawnValues.get(0));
                int sy = Integer.parseInt(spawnValues.get(1));
                int sz = Integer.parseInt(spawnValues.get(2));
                spawn = new int[]{sx, sy, sz};
            } catch (NumberFormatException e) {
                TextUtils.send(player, "<color:#e98df5>ℹ</color> <red>Invalid spawn coordinates. Must be integers.</red>");
                return;
            }
        } else if (!spawnValues.isEmpty()) {
            TextUtils.send(player, "<color:#e98df5>ℹ</color> <red>Missing values for --spawn flag. Usage: --spawn <x> <y> <z></red>");
            return;
        }

        List<String> gameruleValues = flags.getValues("gamerule");
        gameruleValues.addAll(flags.getValues("g"));
        if (!gameruleValues.isEmpty()) {
            if (gameruleValues.size() % 2 != 0) {
                TextUtils.send(player, "<color:#e98df5>ℹ</color> <red>Missing value for --gamerule flag. Usage: --gamerule <name> <value></red>");
                return;
            }
            for (int i = 0; i < gameruleValues.size(); i += 2) {
                gameruleStrings.put(gameruleValues.get(i), gameruleValues.get(i + 1));
            }
        }

        if (api.worldExists(namespacedKey)) {
            TextUtils.send(player, "<red>❗ World creation failed:</red> <gray>Namespace <namespace> is already taken.</gray>", Placeholder.unparsed("namespace", namespacedKey.asString()));
            TextUtils.send(player, "<red>All namespaces taken for reference: </red><white>" + String.join("</white><gray>, </gray><white>", api.getAllWorlds().stream().map(NamespacedKey::asString).toList()) + "</white>");
            return;
        }

        TextUtils.send(player, "<yellow>Creating world <white><world></white>...</yellow>", Placeholder.unparsed("world", namespacedKey.asString()));

        WorldBuilder builder = api.builder(namespacedKey);
        if (presetName != null) builder.preset(presetName);
        if (env != null) builder.environment(env);
        if (seedStr != null) builder.seed(seedStr);
        if (difficulty != null) builder.difficulty(difficulty);
        builder.generateStructures(generateStructures);
        if (spawn != null) builder.spawn(spawn[0], spawn[1], spawn[2]);

        for (Map.Entry<String, String> entry : gameruleStrings.entrySet()) {
            String snakeCase = entry.getKey().replaceAll("([a-z])([A-Z]+)", "$1_$2").toLowerCase(Locale.ROOT);
            NamespacedKey ruleKey = NamespacedKey.fromString(snakeCase);
            if (ruleKey == null) {
                TextUtils.send(player, "<color:#e98df5>ℹ</color> <yellow>Skipping invalid gamerule name: <white><name></white></yellow>", Placeholder.unparsed("name", entry.getKey()));
                continue;
            }
            GameRule<?> rule = Registry.GAME_RULE.get(ruleKey);
            if (rule == null) {
                TextUtils.send(player, "<color:#e98df5>ℹ</color> <yellow>Unknown gamerule: <white><name></white></yellow>", Placeholder.unparsed("name", entry.getKey()));
                continue;
            }
            if (!applyGameruleToBuilder(builder, rule, entry.getValue())) {
                TextUtils.send(player, "<color:#e98df5>ℹ</color> <red>Invalid value for gamerule <white><name></white>: <white><value></white></red>",
                        Placeholder.unparsed("name", entry.getKey()), Placeholder.unparsed("value", entry.getValue()));
                return;
            }
        }

        builder.create().thenAccept(managedWorld -> {
            context.getWebhookService().notify("WORLD_CREATE", player, "Created dimension " + namespacedKey.asString());

            managedWorld.getBukkitWorld().ifPresent(world -> {
                player.teleportAsync(world.getSpawnLocation()).thenAccept(success -> {
                    if (success) {
                        TextUtils.send(player, "<green>Created and teleported to world <white><world></white>!</green>", Placeholder.unparsed("world", namespacedKey.asString()));
                    } else {
                        TextUtils.send(player, "<red>Failed to safely teleport you to <white><world></white>!</red>", Placeholder.unparsed("world", namespacedKey.asString()));
                    }
                });
            });
        }).exceptionally(e -> {
            Throwable cause = e instanceof java.util.concurrent.CompletionException ? e.getCause() : e;
            TextUtils.send(player, "<red>Failed to create world <world>: <error></red>", Placeholder.unparsed("world", namespacedKey.asString()), Placeholder.unparsed("error", cause.getMessage()));
            context.getPlugin().getLogger().log(java.util.logging.Level.SEVERE, "Failed to create world", cause);
            return null;
        });
    }

    @SuppressWarnings("unchecked")
    private static <T> boolean applyGameruleToBuilder(WorldBuilder builder, GameRule<T> rule, String valueStr) {
        try {
            if (rule.getType() == Boolean.class) {
                if (!valueStr.equalsIgnoreCase("true") && !valueStr.equalsIgnoreCase("false")) {
                    return false;
                }
                builder.gameRule((GameRule<Boolean>) rule, Boolean.parseBoolean(valueStr));
                return true;
            } else if (rule.getType() == Integer.class) {
                builder.gameRule((GameRule<Integer>) rule, Integer.parseInt(valueStr));
                return true;
            }
        } catch (Exception ignored) {}
        return false;
    }
}