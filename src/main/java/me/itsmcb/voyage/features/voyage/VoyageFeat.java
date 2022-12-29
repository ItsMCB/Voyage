package me.itsmcb.voyage.features.voyage;

import me.itsmcb.vexelcore.bukkit.BukkitFeature;
import me.itsmcb.voyage.Voyage;

public class VoyageFeat extends BukkitFeature {

    public VoyageFeat(Voyage instance) {
        super("Voyage CMD", "Voyage help", null, instance);
        registerCommand(new VoyageCmd(instance));
    }
}
