package me.itsmcb.voyage;

import me.itsmcb.vexelcore.bukkit.api.managers.BukkitFeatureManager;
import me.itsmcb.vexelcore.bukkit.api.managers.OldLocalizationManager;
import me.itsmcb.vexelcore.bukkit.api.utils.PluginUtils;
import me.itsmcb.vexelcore.common.api.config.BoostedConfig;
import me.itsmcb.voyage.api.VoyageWorld;
import me.itsmcb.voyage.api.VoyageWorldManager;
import me.itsmcb.voyage.features.chunk.ChunkFeat;
import me.itsmcb.voyage.features.entity.EntityFeat;
import me.itsmcb.voyage.features.voyage.VoyageFeat;
import me.itsmcb.voyage.features.world.WorldFeat;
import me.itsmcb.voyage.hooks.PAPIExpansion;
import me.itsmcb.voyage.worldgen.generators.MoonGenerator;
import me.itsmcb.voyage.worldgen.generators.SuperflatGenerator;
import me.itsmcb.voyage.worldgen.generators.VoidGenerator;
import org.bukkit.configuration.serialization.ConfigurationSerialization;
import org.bukkit.generator.ChunkGenerator;
import org.bukkit.plugin.java.JavaPlugin;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.util.ArrayList;
import java.util.Arrays;

public final class Voyage extends JavaPlugin {

    private Voyage instance;
    private OldLocalizationManager localizationManager;
    private BoostedConfig mainConfig;
    private BukkitFeatureManager featureManager;
    private VoyageWorldManager worldManager;

    public OldLocalizationManager getLocalizationManager() {
        return localizationManager;
    }
    public BoostedConfig getMainConfig() {
        return mainConfig;
    }

    public VoyageWorldManager getWorldManager() {
        return worldManager;
    }

    @Override
    public void onEnable() {
        this.instance = this;
        // TODO worlds with "keep-loaded" should be loaded when the server starts

        // Load configurations and options
        // todo hook into future localization plugin to get default server language
        this.localizationManager = new OldLocalizationManager(this, "en_US");
        localizationManager.register("en_US");

        // Config
        //YamlSerializer spigotSerializer = new SpigotSerializer();
        //mainConfig = new BoostedConfig(getDataFolder(),"config", getResource("config.yml"), spigotSerializer);

        // World configs
        ConfigurationSerialization.registerClass(VoyageWorld.class, "VoyageWorld");
        this.worldManager = new VoyageWorldManager(getDataFolder());

        // Register features
        this.featureManager = new BukkitFeatureManager();
        featureManager.register(new ChunkFeat(instance));
        featureManager.register(new EntityFeat(instance));
        featureManager.register(new WorldFeat(instance));
        featureManager.register(new VoyageFeat(instance));
        featureManager.reload();

        // Hook into plugins after all have loaded
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
        if (PluginUtils.pluginIsLoaded("PlaceholderAPI")) {
            new PAPIExpansion(instance).register();
        }
    }
}
