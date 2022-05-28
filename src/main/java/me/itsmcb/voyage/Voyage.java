package me.itsmcb.voyage;

import me.itsmcb.voyage.commands.Main;
import me.itsmcb.voyage.commands.WorldCMD;
import me.itsmcb.voyage.worldgen.generators.VoidGenerator;
import org.bukkit.generator.ChunkGenerator;
import org.bukkit.plugin.java.JavaPlugin;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

public final class Voyage extends JavaPlugin {

    private Voyage instance;
    private boolean enableDebug;

    public Voyage getInstance() {
        return instance;
    }

    public boolean getEnableDebug() {
        return enableDebug;
    }

    @Override
    public void onEnable() {
        this.instance = this;
        /*
        // Load config
        saveDefaultConfig();
        // Check if debug should be enabled
        this.enableDebug = getConfig().getBoolean("enable_debug");
         */
        // Set commands
        getCommand("voyage").setExecutor(new Main(instance));
        getCommand("world").setExecutor(new WorldCMD(instance));
    }

    @Override
    public @Nullable ChunkGenerator getDefaultWorldGenerator(@NotNull String worldName, @Nullable String id) {
        return new VoidGenerator();
    }
}
