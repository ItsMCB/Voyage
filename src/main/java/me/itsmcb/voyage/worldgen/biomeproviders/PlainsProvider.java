package me.itsmcb.voyage.worldgen.biomeproviders;

import com.google.common.collect.Lists;
import org.bukkit.block.Biome;
import org.bukkit.generator.BiomeProvider;
import org.bukkit.generator.WorldInfo;

import java.util.List;

public class PlainsProvider extends BiomeProvider {

    // TODO change to be "BiomeProvider" and not hardcode Plains biome
    @Override
    public Biome getBiome(WorldInfo worldInfo, int x, int y, int z) {
        return Biome.PLAINS;
    }

    @Override
    public List<Biome> getBiomes(WorldInfo worldInfo) {
        return Lists.newArrayList(Biome.PLAINS);
    }
}
