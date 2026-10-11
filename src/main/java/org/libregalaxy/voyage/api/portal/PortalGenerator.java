package org.libregalaxy.voyage.api.portal;

import org.bukkit.Location;

public interface PortalGenerator {
    /**
     * Handles physical portal block placement.
     * Returns the exact location the player should be teleported to.
     */
    Location findOrCreate(Location targetLoc);
}