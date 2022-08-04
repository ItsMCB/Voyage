package me.itsmcb.voyage.features.world;

import me.itsmcb.vexelcore.bukkit.BukkitFeature;
import me.itsmcb.voyage.Voyage;

public class WorldCMDFeature extends BukkitFeature {

    public WorldCMDFeature(Voyage instance) {
        super("World CMD", "World management", null, instance);
        registerCommand(new WorldCMD(instance));
    }
}
