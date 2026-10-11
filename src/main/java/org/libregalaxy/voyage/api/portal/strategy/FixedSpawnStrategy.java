package org.libregalaxy.voyage.api.portal.strategy;

import org.bukkit.Location;
import org.bukkit.World;
import org.libregalaxy.voyage.api.portal.CoordinateStrategy;

public class FixedSpawnStrategy implements CoordinateStrategy {
    @Override
    public Location calculate(Location from, World target) {
        return target.getSpawnLocation(); // Great for boss rooms and End portals
    }
}