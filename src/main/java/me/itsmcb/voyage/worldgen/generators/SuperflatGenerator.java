package me.itsmcb.voyage.worldgen.generators;

import me.itsmcb.voyage.worldgen.biomeproviders.PlainsProvider;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.World;
import org.bukkit.generator.BiomeProvider;
import org.bukkit.generator.ChunkGenerator;
import org.bukkit.generator.WorldInfo;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.util.Random;

public class SuperflatGenerator extends ChunkGenerator {

    private int height;

    public SuperflatGenerator(int height) {
        this.height = height;
    }

    @Override
    public boolean shouldGenerateNoise() {
        return false;
    }

    @Override
    public boolean shouldGenerateSurface() {
        return false;
    }

    @Override
    public boolean shouldGenerateBedrock() {
        return false;
    }

    @Override
    public boolean shouldGenerateCaves() {
        return false;
    }

    @Override
    public boolean shouldGenerateDecorations() {
        return false;
    }

    @Override
    public boolean shouldGenerateMobs() {
        return true;
    }

    @Override
    public boolean shouldGenerateStructures() {
        return true;
    }

    @Override
    public boolean canSpawn(World world, int x, int z) {
        return true;
    }

    @Override
    public @Nullable BiomeProvider getDefaultBiomeProvider(@NotNull WorldInfo worldInfo) {
        return new PlainsProvider();
    }

    @Override
    public void generateNoise(@NotNull WorldInfo worldInfo, @NotNull Random random, int chunkX, int chunkZ, @NotNull ChunkData chunkData) {
        for (int x = 0; x < 16; x++) {
            for (int z = 0; z < 16; z++) {
                for (int y = chunkData.getMinHeight(); y < height; y++) {
                    chunkData.setBlock(x,y,z, Material.DIRT);
                    if (y == height-1) {
                        chunkData.setBlock(x,y,z, Material.GRASS_BLOCK);
                    }
                    if (y == chunkData.getMinHeight()) {
                        chunkData.setBlock(x,y,z, Material.BEDROCK);
                    }
                }
            }
        }
    }

    @Override
    public Location getFixedSpawnLocation(World world, Random random) {
        final Location spawnLocation = new Location(world, 0.5D, height, 0.5D);
        final Location blockLocation = spawnLocation.clone().subtract(0D, 1D, 0D);
        blockLocation.getBlock().setType(Material.BEDROCK);
        return spawnLocation;
    }
}
