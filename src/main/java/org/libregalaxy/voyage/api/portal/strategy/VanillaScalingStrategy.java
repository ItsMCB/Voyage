package org.libregalaxy.voyage.api.portal.strategy;

import org.bukkit.Location;
import org.bukkit.World;
import org.libregalaxy.voyage.api.portal.CoordinateStrategy;
import org.jetbrains.annotations.NotNull;

public class VanillaScalingStrategy implements CoordinateStrategy {
    private final double scaleFactor;

    public VanillaScalingStrategy(double scaleFactor) {
        if (scaleFactor <= 0.0) {
            throw new IllegalArgumentException("Voyage scale factor must be a positive number! Provided: " + scaleFactor);
        }
        this.scaleFactor = scaleFactor;
    }

    public double getScaleFactor() { return scaleFactor; }

    @Override
    public @NotNull Location calculate(@NotNull Location from, @NotNull World target) {
        return new Location(target, from.getX() * scaleFactor, from.getY(), from.getZ() * scaleFactor, from.getYaw(), from.getPitch());
    }
}