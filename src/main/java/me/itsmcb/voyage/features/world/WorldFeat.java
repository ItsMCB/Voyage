package me.itsmcb.voyage.features.world;

import me.itsmcb.vexelcore.bukkit.BukkitFeature;
import me.itsmcb.voyage.Voyage;

public class WorldFeat extends BukkitFeature {

    public WorldFeat(Voyage instance) {
        super("World CMD", "World management tools", null, instance);
        registerCommand(new WorldCmd(instance));
    }
}
