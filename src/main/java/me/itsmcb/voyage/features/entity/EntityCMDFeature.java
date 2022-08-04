package me.itsmcb.voyage.features.entity;

import me.itsmcb.vexelcore.bukkit.BukkitFeature;
import me.itsmcb.voyage.Voyage;

public class EntityCMDFeature extends BukkitFeature {

    public EntityCMDFeature(Voyage instance) {
        super("Entity CMD", "Entity manipulation", null, instance);
        registerCommand(new EntityCMD(instance));
    }

}
