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


public class VoidGenerator extends ChunkGenerator {

    // Useful resource:
    // https://bukkit.fandom.com/wiki/Developing_a_World_Generator_Plugin
    // https://www.spigotmc.org/threads/1-17-1-world-generator-api.521870/

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
    public Location getFixedSpawnLocation(World world, Random random) {
        final Location spawnLocation = new Location(world, 0.5D, 64.0D, 0.5D);
        final Location blockLocation = spawnLocation.clone().subtract(0D, 1D, 0D);
        blockLocation.getBlock().setType(Material.BEDROCK);
        return spawnLocation;
    }


}
