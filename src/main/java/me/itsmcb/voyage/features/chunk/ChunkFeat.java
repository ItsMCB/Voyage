package me.itsmcb.voyage.features.chunk;

import me.itsmcb.vexelcore.bukkit.BukkitFeature;
import me.itsmcb.voyage.Voyage;

public class ChunkFeat extends BukkitFeature {

    Voyage instance;

    public ChunkFeat(Voyage instance) {
        super("Chunk", "View chunk information", null, instance);
        this.instance = instance;
        registerCommand(new ChunkCmd(instance));
    }

}
