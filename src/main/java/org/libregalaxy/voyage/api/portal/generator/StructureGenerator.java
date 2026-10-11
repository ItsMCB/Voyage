package org.libregalaxy.voyage.api.portal.generator;

import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.NamespacedKey;
import org.bukkit.block.structure.Mirror;
import org.bukkit.block.structure.StructureRotation;
import org.bukkit.structure.Structure;
import org.libregalaxy.voyage.api.portal.PortalGenerator;

import java.util.concurrent.ThreadLocalRandom;

public class StructureGenerator implements PortalGenerator {
    private final NamespacedKey structureKey;

    public StructureGenerator(NamespacedKey structureKey) {
        this.structureKey = structureKey;
    }

    public NamespacedKey getStructureKey() { return structureKey; }

    @Override
    public Location findOrCreate(Location targetLoc) {
        // Place structure synchronously before player arrives to prevent suffocation or falling into the void
        try {
            Structure structure = Bukkit.getStructureManager().loadStructure(structureKey);
            if (structure != null) {
                structure.place(targetLoc, true, StructureRotation.NONE, Mirror.NONE, 0, 1.0F, ThreadLocalRandom.current());
            } else {
                Bukkit.getLogger().severe("Missing portal structure file: " + structureKey);
            }
        } catch (Exception e) {
            Bukkit.getLogger().warning("Failed to place portal structure: " + e.getMessage());
        }

        return targetLoc;
    }
}