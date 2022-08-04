package me.itsmcb.voyage.features.voyage;

import me.itsmcb.vexelcore.bukkit.BukkitFeature;
import me.itsmcb.voyage.Voyage;

public class VoyageCMDFeature extends BukkitFeature {

    public VoyageCMDFeature(Voyage instance) {
        super("Voyage CMD", "Voyage help", null, instance);
        registerCommand(new VoyageCMD(instance));
    }
}
