package me.itsmcb.voyage;

import org.bukkit.plugin.java.JavaPlugin;

public final class Voyage extends JavaPlugin {

    @Override
    public void onEnable() {
        // Plugin startup logic
        System.out.println("\n\nVoyage is alive!\n\n");
    }

    @Override
    public void onDisable() {
        // Plugin shutdown logic
    }
}
