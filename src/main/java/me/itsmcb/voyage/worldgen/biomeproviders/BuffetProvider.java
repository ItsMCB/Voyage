package me.itsmcb.voyage.worldgen.biomeproviders;

import org.apache.commons.lang3.Validate;
import org.bukkit.block.Biome;
import org.bukkit.generator.BiomeProvider;
import org.bukkit.generator.WorldInfo;
import org.jetbrains.annotations.NotNull;

import java.util.List;

public class BuffetProvider extends BiomeProvider {

    private final Biome biome;

    public BuffetProvider(@NotNull Biome biome) {
        Validate.notNull(biome, "Biome can't be null!");
        this.biome = biome;
    }

    @NotNull
    @Override
    public Biome getBiome(@NotNull WorldInfo worldInfo, int x, int y, int z) {
        return biome;
    }

    @NotNull
    @Override
    public List<Biome> getBiomes(@NotNull WorldInfo worldInfo) {
        return List.of(biome);
    }
}
