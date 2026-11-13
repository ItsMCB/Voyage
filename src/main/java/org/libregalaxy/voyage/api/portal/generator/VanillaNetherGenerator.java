package org.libregalaxy.voyage.api.portal.generator;

import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.World;
import org.bukkit.block.Block;
import org.libregalaxy.voyage.api.VoyageAPI;
import org.libregalaxy.voyage.api.portal.PortalGenerator;

public class VanillaNetherGenerator implements PortalGenerator {

    private static final int PORTAL_SEARCH_RADIUS = 16;

    @Override
    public Location findOrCreate(Location targetLoc) {
        if (targetLoc == null) {
            return null;
        }

        World world = targetLoc.getWorld();
        if (world == null) {
            return targetLoc;
        }

        try {
            if (world.getMaxHeight() <= world.getMinHeight() + 2) {
                return targetLoc;
            }
        } catch (Throwable ignored) {
            return targetLoc;
        }

        // 1. Search nearby for an existing Nether portal block
        Location existing = findExistingPortal(world, targetLoc);
        if (existing != null) {
            return existing;
        }

        // 2. Find a safe landing spot and construct a standard 4x5 obsidian Nether portal frame
        Location safe = VoyageAPI.getSafeLocation(targetLoc);
        buildNetherPortalFrame(world, safe);
        return safe;
    }

    private Location findExistingPortal(World world, Location targetLoc) {
        int cx = targetLoc.getBlockX();
        int cy = Math.clamp(targetLoc.getBlockY(), world.getMinHeight() + 1, world.getMaxHeight() - 2);
        int cz = targetLoc.getBlockZ();

        int minY = Math.max(world.getMinHeight() + 1, cy - PORTAL_SEARCH_RADIUS);
        int maxY = Math.min(world.getMaxHeight() - 2, cy + PORTAL_SEARCH_RADIUS);

        Location best = null;
        double bestDistSq = Double.MAX_VALUE;

        for (int dx = -PORTAL_SEARCH_RADIUS; dx <= PORTAL_SEARCH_RADIUS; dx++) {
            for (int dz = -PORTAL_SEARCH_RADIUS; dz <= PORTAL_SEARCH_RADIUS; dz++) {
                for (int y = minY; y <= maxY; y++) {
                    Block b = world.getBlockAt(cx + dx, y, cz + dz);
                    if (b.getType() == Material.NETHER_PORTAL) {
                        Block below = world.getBlockAt(cx + dx, y - 1, cz + dz);
                        if (below.getType() != Material.NETHER_PORTAL) {
                            double distSq = (dx * dx) + ((y - cy) * (y - cy)) + (dz * dz);
                            if (distSq < bestDistSq) {
                                bestDistSq = distSq;
                                best = new Location(world, cx + dx + 0.5, y, cz + dz + 0.5,
                                        targetLoc.getYaw(), targetLoc.getPitch());
                            }
                        }
                    }
                }
            }
        }
        return best;
    }

    private void buildNetherPortalFrame(World world, Location safeLoc) {
        int bx = safeLoc.getBlockX();
        int by = Math.clamp(safeLoc.getBlockY(), world.getMinHeight() + 2, world.getMaxHeight() - 5);
        int bz = safeLoc.getBlockZ();

        // Ensure a 3x4 obsidian platform underneath the portal so players never step out into void/lava
        for (int x = -1; x <= 2; x++) {
            for (int z = -1; z <= 1; z++) {
                Block platform = world.getBlockAt(bx + x, by - 1, bz + z);
                if (!VoyageAPI.isSafeFloor(platform)) {
                    platform.setType(Material.OBSIDIAN, false);
                }
                // Clear headspace in front of and behind the portal
                for (int y = 0; y <= 2; y++) {
                    if (z != 0) {
                        Block airSpace = world.getBlockAt(bx + x, by + y, bz + z);
                        if (!airSpace.getType().isAir()) {
                            airSpace.setType(Material.AIR, false);
                        }
                    }
                }
            }
        }

        // Build 4-wide (x = -1..2) by 5-tall (y = -1..3) obsidian frame with 2x3 NETHER_PORTAL interior
        for (int x = -1; x <= 2; x++) {
            for (int y = -1; y <= 3; y++) {
                Block frameOrPortal = world.getBlockAt(bx + x, by + y, bz);
                boolean isBorder = (x == -1 || x == 2 || y == -1 || y == 3);
                if (isBorder) {
                    frameOrPortal.setType(Material.OBSIDIAN, false);
                } else {
                    frameOrPortal.setType(Material.NETHER_PORTAL, false);
                }
            }
        }
    }
}