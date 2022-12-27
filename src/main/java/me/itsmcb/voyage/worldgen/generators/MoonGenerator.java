package me.itsmcb.voyage.worldgen.generators;

import me.itsmcb.vexelcore.common.api.utils.MathUtils;
import me.itsmcb.voyage.worldgen.biomeproviders.PlainsProvider;
import me.itsmcb.voyage.worldgen.populators.SpawnerPopulator;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.World;
import org.bukkit.generator.BiomeProvider;
import org.bukkit.generator.BlockPopulator;
import org.bukkit.generator.ChunkGenerator;
import org.bukkit.generator.WorldInfo;
import org.bukkit.util.noise.SimplexOctaveGenerator;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.util.ArrayList;
import java.util.List;
import java.util.Random;

public class MoonGenerator extends ChunkGenerator {

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
        return false;
    }

    @Override
    public boolean shouldGenerateStructures() {
        return false;
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
    public @NotNull List<BlockPopulator> getDefaultPopulators(@NotNull World world) {
        return new ArrayList<>(){{add(new SpawnerPopulator());}};
    }

    @Override
    public void generateNoise(@NotNull WorldInfo worldInfo, @NotNull Random random, int chunkX, int chunkZ, @NotNull ChunkGenerator.ChunkData chunkData) {
        SimplexOctaveGenerator generator = new SimplexOctaveGenerator(new Random(worldInfo.getSeed()), 2);
        generator.setScale(0.008);

        int worldX = chunkX * 16;
        int worldZ = chunkZ * 16;

        for (int x = 0; x < 16; x++) {
            for (int z = 0; z < 16; z++) {
                generateMoon(generator, worldX, worldZ, chunkData, x, z);
            }
        }
    }

    @Override
    public Location getFixedSpawnLocation(World world, Random random) {
        final Location spawnLocation = new Location(world, 0.5D, 64, 0.5D);
        final Location blockLocation = spawnLocation.clone().subtract(0D, 1D, 0D);
        blockLocation.getBlock().setType(Material.BEDROCK);
        return spawnLocation;
    }


    private void generateMoon(SimplexOctaveGenerator generator, int worldX, int worldZ, ChunkData chunkData, int x, int z) {
        Material cobblestone = Material.COBBLESTONE;
        Material deadBrainCoral = Material.DEAD_BRAIN_CORAL_BLOCK;

        double noise = generator.noise(worldX + x, worldZ + z, .35, 3, true);
        int height = (int) (noise * 40);
        height += 84;
        if (height > chunkData.getMaxHeight()) {
            height = chunkData.getMaxHeight();
        }
        for (int y = 0; y < height; y++) {
            int randomStone = MathUtils.randomIntBetween(1,20);
            if (randomStone <= 17) {
                chunkData.setBlock(x,y,z, deadBrainCoral);
            }
            if (randomStone == 18) {
                chunkData.setBlock(x,y,z, Material.MAGMA_BLOCK);
            }
            if (randomStone >= 19) {
                chunkData.setBlock(x, y, z, cobblestone);
            }
            if ((y == height-1) && (MathUtils.randomIntBetween(1,300) == 1)) {
                chunkData.setBlock(x,y+1,z, Material.SPAWNER);
                for (int blkx = x-2; blkx < x+2; blkx++) {
                    for (int blkz = z-2; blkz < z+2; blkz++) {
                        for (int blky = y-2; blky < y+1; blky++) {
                            chunkData.setBlock(blkx,blky,blkz, Material.OBSIDIAN);
                        }
                    }
                }
            }
        }
    }
}
