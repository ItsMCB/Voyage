package org.libregalaxy.voyage.api.portal.generator;

import org.bukkit.Location;
import org.libregalaxy.voyage.api.portal.PortalGenerator;

public class NoneGenerator implements PortalGenerator {
    @Override
    public Location findOrCreate(Location targetLoc) {
        // Drops the player right at the location without generating any blocks
        return targetLoc;
    }
}