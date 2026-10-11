package org.libregalaxy.voyage.api.portal.generator;

import org.bukkit.Location;
import org.libregalaxy.voyage.api.portal.PortalGenerator;

public class VanillaNetherGenerator implements PortalGenerator {
    @Override
    public Location findOrCreate(Location targetLoc) {
        // Return the target. The listener will tell Paper's native engine to handle the rest!
        return targetLoc;
    }
}