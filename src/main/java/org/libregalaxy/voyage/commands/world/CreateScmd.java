package org.libregalaxy.voyage.commands.world;

import com.mojang.brigadier.context.CommandContext;
import io.papermc.paper.command.brigadier.CommandSourceStack;
import io.papermc.paper.command.brigadier.argument.ArgumentTypes;
import me.itsmcb.vexelcore.bukkit.api.framework.FlagParser;
import me.itsmcb.vexelcore.bukkit.api.framework.TextUtils;
import me.itsmcb.vexelcore.bukkit.api.framework.VexelArg;
import me.itsmcb.vexelcore.bukkit.api.framework.VexelFlag;
import me.itsmcb.vexelcore.bukkit.api.framework.VexelFlags;
import me.itsmcb.vexelcore.bukkit.api.framework.VexelPlayerSubCommand;
import net.kyori.adventure.text.minimessage.tag.resolver.Placeholder;
import org.bukkit.Difficulty;
import org.bukkit.GameRule;
import org.bukkit.NamespacedKey;
import org.bukkit.Registry;
import org.bukkit.World;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.entity.Player;
import org.jetbrains.annotations.NotNull;
import org.libregalaxy.voyage.VoyageContext;
import org.libregalaxy.voyage.api.VoyageAPI;
import org.libregalaxy.voyage.api.builder.WorldBuilder;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;

public class CreateScmd extends VexelPlayerSubCommand<VoyageContext> {

    private final VexelArg<NamespacedKey> KEY_ARG;
    private final VexelFlags creationFlags;
    private final VexelArg<String> FLAGS_ARG;

    public CreateScmd(@NotNull VoyageContext context) {
        super(context, "create", "Generates a new dimension.", "voyage.create");
        this.KEY_ARG = VexelArg.builder("namespacedkey", ArgumentTypes.namespacedKey(), NamespacedKey.class, "Identifier for the new world (e.g., \"custom:lobby\")")
                .build();

        this.creationFlags = VexelFlags.builder()
                .flag(VexelFlag.valued("preset", "World preset template", ctx -> {
                    List<String> list = new ArrayList<>(List.of("void", "flat", "amplified"));
                    ConfigurationSection presets = context.getPlugin().getConfig().getConfigurationSection("presets");
                    if (presets != null) {
                        for (String key : presets.getKeys(false)) {
                            if (!list.contains(key)) list.add(key);
                        }
                    }
                    return list;
                }).alias("p"))
                .flag(VexelFlag.valued("environment", "Dimension environment", List.of("NORMAL", "NETHER", "THE_END"))
                        .alias("e"))
                .flag(VexelFlag.valued("seed", "World seed")
                        .alias("s"))
                .flag(VexelFlag.valued("difficulty", "Dimension difficulty", List.of("PEACEFUL", "EASY", "NORMAL", "HARD"))
                        .alias("d"))
                .flag(VexelFlag.booleanFlag("no-structures", "Disable structure generation"))
                .flag(VexelFlag.multiValued("spawn", "Fixed spawn coordinates <x> <y> <z>", 3))
                .flag(VexelFlag.multiValued("gamerule", "GameRule override <name> <value>", 2, (ctx, valueIndex, prev) -> {
                    if (valueIndex == 0) {
                        List<String> rules = new ArrayList<>();
                        for (GameRule<?> r : Registry.GAME_RULE) {
                            rules.add(r.getKey().value());
                        }
                        return rules;
                    } else if (valueIndex == 1 && !prev.isEmpty()) {
                        String ruleName = prev.get(0).toLowerCase(Locale.ROOT);
                        String snakeCase = ruleName.replaceAll("([a-z])([A-Z]+)", "$1_$2");
                        NamespacedKey ruleKey = NamespacedKey.fromString(snakeCase);
                        GameRule<?> rule = ruleKey != null ? Registry.GAME_RULE.get(ruleKey) : null;
                        if (rule != null) {
                            if (rule.getType() == Boolean.class) {
                                return List.of("true", "false");
                            } else if (rule.getType() == Integer.class) {
                                return List.of("0", "1", "3", "5");
                            }
                        }
                    }
                    return Collections.emptyList();
                }).alias("g").repeatable())
                .build();

        this.FLAGS_ARG = creationFlags.asArg("flags",
                "Optional flags: --preset <name>, --environment <env>, --seed <seed>, --difficulty <diff>, --no-structures, --spawn <x> <y> <z>, --gamerule <name> <value>");
    }

    @Override public @NotNull List<VexelArg<?>> getArguments() { return List.of(KEY_ARG, FLAGS_ARG); }

    @Override
    public void execute(@NotNull CommandContext<CommandSourceStack> ctx, @NotNull Player player) {
        NamespacedKey namespacedKey = requireArg(ctx, KEY_ARG);
        String flagsInput = optionalArg(ctx, FLAGS_ARG);
        VoyageAPI api = context.getWorldManager();

        FlagParser flags = creationFlags.parse(flagsInput);

        World.Environment env = null;
        String seedStr = null;
        Difficulty difficulty = null;
        boolean generateStructures = true;
        int[] spawn = null;
        Map<String, String> gameruleStrings = new LinkedHashMap<>();

        Optional<String> presetOpt = flags.getValue("preset");
        String presetName = presetOpt.orElse(null);

        Optional<String> envOpt = flags.getValue("environment");
        if (envOpt.isPresent()) {
            String envStr = envOpt.get().toUpperCase(Locale.ROOT);
            try {
                env = World.Environment.valueOf(envStr);
            } catch (IllegalArgumentException e) {
                TextUtils.send(player, "<color:#e98df5>ℹ</color> <red>Invalid environment: <white><env></white>. Valid options: NORMAL, NETHER, THE_END, CUSTOM</red>", Placeholder.unparsed("env", envStr));
                return;
            }
        }

        Optional<String> seedOpt = flags.getValue("seed");
        if (seedOpt.isPresent()) {
            seedStr = seedOpt.get();
        }

        Optional<String> diffOpt = flags.getValue("difficulty");
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