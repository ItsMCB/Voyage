package me.itsmcb.voyage.features.chunk;

import me.itsmcb.vexelcore.bukkit.BukkitFeature;
import me.itsmcb.voyage.Voyage;

public class ChunkCMDFeature extends BukkitFeature {

    public ChunkCMDFeature(Voyage instance) {
        super("Chunk CMD", "View chunk information", null, instance);
        registerCommand(new ChunkCMD(instance));
    }
}
