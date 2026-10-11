package org.libregalaxy.voyage.api.portal;

import org.bukkit.Location;
import org.bukkit.World;

public interface CoordinateStrategy {
    Location calculate(Location from, World targetBukkitWorld);
}