package me.itsmcb.voyage.worldgen.populators;

import org.bukkit.Material;
import org.bukkit.block.BlockState;
import org.bukkit.block.CreatureSpawner;
import org.bukkit.entity.EntityType;
import org.bukkit.generator.BlockPopulator;
import org.bukkit.generator.LimitedRegion;
import org.bukkit.generator.WorldInfo;
import org.jetbrains.annotations.NotNull;

import java.util.List;
import java.util.Random;

public class SpawnerPopulator extends BlockPopulator {

    @Override
    public void populate(@NotNull WorldInfo worldInfo, @NotNull Random random, int chunkX, int chunkZ, @NotNull LimitedRegion limitedRegion) {
        List<BlockState> entities = limitedRegion.getTileEntities();
        for(BlockState blockState : entities){
            if(blockState.getBlockData().getMaterial().equals(Material.SPAWNER)){
                CreatureSpawner spawner = (CreatureSpawner) blockState;
                spawner.setSpawnedType(EntityType.SPIDER);
                spawner.setRequiredPlayerRange(16);
                spawner.update(true, false);
            }
        }
    }
}
