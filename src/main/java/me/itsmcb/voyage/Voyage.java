package me.itsmcb.voyage;

import me.itsmcb.vexelcore.bukkit.api.managers.BukkitFeatureManager;
import me.itsmcb.vexelcore.bukkit.api.utils.HookUtils;
import me.itsmcb.voyage.features.chunk.ChunkCMDFeature;
import me.itsmcb.voyage.features.entity.EntityCMDFeature;
import me.itsmcb.voyage.features.voyage.VoyageCMDFeature;
import me.itsmcb.voyage.features.world.WorldCMDFeature;
import me.itsmcb.voyage.hooks.PAPIExpansion;
import me.itsmcb.voyage.worldgen.generators.MoonGenerator;
import me.itsmcb.voyage.worldgen.generators.SuperflatGenerator;
import me.itsmcb.voyage.worldgen.generators.VoidGenerator;
import org.bukkit.generator.ChunkGenerator;
import org.bukkit.plugin.java.JavaPlugin;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.UUID;

public final class Voyage extends JavaPlugin {

    private Voyage instance;
    private boolean enableDebug;
    private BukkitFeatureManager featureManager;

    public Voyage getInstance() {
        return instance;
    }

    public boolean getEnableDebug() {
        return enableDebug;
    }

    public HashMap<UUID, UUID> selectedEntities = new HashMap<>();

    @Override
    public void onEnable() {
        this.instance = this;
        this.featureManager = new BukkitFeatureManager();
        featureManager.register(new ChunkCMDFeature(instance));
        featureManager.register(new EntityCMDFeature(instance));
        featureManager.register(new WorldCMDFeature(instance));
        featureManager.register(new VoyageCMDFeature(instance));
        featureManager.reload();
        /*
        // Load config
        saveDefaultConfig();
        // Check if debug should be enabled
        this.enableDebug = getConfig().getBoolean("enable_debug");
         */

        // Using scheduler to delay running the hooks until the server (i.e. plugins that will be hooked into) has finished loading
        getServer().getScheduler().scheduleSyncDelayedTask(this, this::registerPluginHooks);
    }

    @Override
    public @Nullable ChunkGenerator getDefaultWorldGenerator(@NotNull String worldName, @Nullable String id) {
        if (id != null) {
            System.out.println("Loading \""+worldName+"\" with generator \""+id+"\"");
            if (id.equalsIgnoreCase("void")) {
                return new VoidGenerator();
            }
            if (id.contains("superflat")) {
                ArrayList<String> args = new ArrayList<>(Arrays.asList(id.split(":")));
                args.remove(0);
                if (args.size() == 0) {
                    return new SuperflatGenerator(64);
                }
                return new SuperflatGenerator(Integer.parseInt(args.get(0)));
            }
            if (id.equalsIgnoreCase("moon")) {
                return new MoonGenerator();
            }
        }
        System.out.println("\""+worldName+"\" is not using a valid generator ID. Using void generator as a fallback.");
        return new VoidGenerator();
    }

    private void registerPluginHooks() {
        if (HookUtils.pluginIsLoaded("PlaceholderAPI")) {
            new PAPIExpansion(instance).register();
        } else {
            System.out.println("Unable to hook into PlaceholderAPI");
        }
    }
}
