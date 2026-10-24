package org.libregalaxy.voyage;

import me.itsmcb.vexelcore.bukkit.api.framework.VexelCommandRegistry;
import org.bukkit.HeightMap;
import org.bukkit.World;
import org.bukkit.block.Biome;
import org.bukkit.entity.Player;
import org.bukkit.generator.BiomeProvider;
import org.bukkit.generator.ChunkGenerator;
import org.bukkit.generator.WorldInfo;
import org.bukkit.plugin.ServicePriority;
import org.bukkit.plugin.java.JavaPlugin;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;
import org.libregalaxy.voyage.api.VoyageAPI;
import org.libregalaxy.voyage.commands.voyage.VoyageCmd;
import org.libregalaxy.voyage.commands.world.WorldCmd;
import org.libregalaxy.voyage.core.PlayerDataManager;
import org.libregalaxy.voyage.core.PlayerStateSerializer;
import org.libregalaxy.voyage.core.VoyageWorldListener;
import org.libregalaxy.voyage.core.VoyageWorldManager;
import org.libregalaxy.voyage.core.WebhookService;
import org.libregalaxy.voyage.core.database.VoyageDatabaseService;
import org.libregalaxy.voyage.listeners.VoyagePortalListener;

import java.util.List;
import java.util.Random;

public final class VoyagePlugin extends JavaPlugin {

    public static final ChunkGenerator VOID_CHUNK_GENERATOR = new VanillaVoidChunkGenerator();
    public static final BiomeProvider VOID_BIOME_PROVIDER = new VanillaVoidBiomeProvider();

    private VoyageContext context;

    @Override
    public void onEnable() {
        saveDefaultConfig();

        this.context = new VoyageContext(this);

        // 1. Initialize embedded SQLite database engine
        VoyageDatabaseService databaseService = new VoyageDatabaseService(context);
        context.registerService(databaseService);

        WebhookService webhookService = new WebhookService(context);
        context.registerService(webhookService);

        VoyageWorldManager worldManager = new VoyageWorldManager(context);
        context.registerService(worldManager);

        // Register VoyageAPI for dependent plugins
        getServer().getServicesManager().register(VoyageAPI.class, worldManager, this, ServicePriority.Normal);

        PlayerStateSerializer stateSerializer = new PlayerStateSerializer(context);
        context.registerService(stateSerializer);

        PlayerDataManager playerDataManager = new PlayerDataManager(context, stateSerializer);
        context.registerService(playerDataManager);

        playerDataManager.loadAll();

        getServer().getPluginManager().registerEvents(playerDataManager, this);
        getServer().getPluginManager().registerEvents(new VoyagePortalListener(worldManager, this), this);
        getServer().getPluginManager().registerEvents(new VoyageWorldListener(worldManager), this);

        VexelCommandRegistry registry = new VexelCommandRegistry(context);
        registry.register(new WorldCmd(context));
        registry.register(new VoyageCmd(context));
    }

    @Override
    public void onDisable() {
        if (context == null) return;

        PlayerDataManager playerDataManager = context.getPlayerDataManager();
        VoyageWorldManager worldManager = context.getWorldManager();
        VoyageDatabaseService databaseService = context.getDatabaseService();

        // STEP 1: Synchronously capture and write all connected player states
        if (playerDataManager != null) {
            for (Player p : getServer().getOnlinePlayers()) {
                try {
                    playerDataManager.savePlayerStateSync(p);
                } catch (Exception e) {
                    getLogger().severe("Failed to final-save state for " + p.getName() + ": " + e.getMessage());
                }
            }

            try {
                playerDataManager.shutdown();
            } catch (Exception e) {
                getLogger().severe("Failed to shutdown PlayerDataManager: " + e.getMessage());
            }
        }

        // STEP 2: Gracefully shutdown the World Manager and eviction tasks
        if (worldManager != null) {
            try {
                worldManager.shutdown();
            } catch (Exception e) {
                getLogger().severe("Failed to gracefully shutdown VoyageWorldManager: " + e.getMessage());
            }
        }

        // STEP 3: Checkpoint and close database connection pool
        if (databaseService != null) {
            try {
                databaseService.shutdown();
            } catch (Exception e) {
                getLogger().severe("Failed to close Voyage database: " + e.getMessage());
            }
        }
    }

    @Override
    public @Nullable ChunkGenerator getDefaultWorldGenerator(@NotNull String worldName, @Nullable String id) {
        if (VoyageAPI.isVoidGenerator(id)) {
            return VOID_CHUNK_GENERATOR;
        }
        return super.getDefaultWorldGenerator(worldName, id);
    }

    /**
     * Chunk generator producing purely empty void chunks on Paper servers.
     * All generation steps are explicitly disabled to prevent superflat block population.
     */
    public static class VanillaVoidChunkGenerator extends ChunkGenerator {
        @Override
        public void generateNoise(@NotNull WorldInfo worldInfo, @NotNull Random random, int chunkX, int chunkZ, @NotNull ChunkData chunkData) {}

        @Override
        public void generateSurface(@NotNull WorldInfo worldInfo, @NotNull Random random, int chunkX, int chunkZ, @NotNull ChunkData chunkData) {}

        @Override
        public void generateBedrock(@NotNull WorldInfo worldInfo, @NotNull Random random, int chunkX, int chunkZ, @NotNull ChunkData chunkData) {}

        @Override
        public void generateCaves(@NotNull WorldInfo worldInfo, @NotNull Random random, int chunkX, int chunkZ, @NotNull ChunkData chunkData) {}

        @Override
        public boolean shouldGenerateNoise() { return false; }

        @Override
        public boolean shouldGenerateSurface() { return false; }

        @Override
        public boolean shouldGenerateBedrock() { return false; }

        @Override
        public boolean shouldGenerateCaves() { return false; }

        @Override
        public boolean shouldGenerateDecorations() { return false; }

        @Override
        public boolean shouldGenerateMobs() { return false; }

        @Override
        public boolean shouldGenerateStructures() { return false; }

        @Override
        public int getBaseHeight(@NotNull WorldInfo worldInfo, @NotNull Random random, int x, int z, @NotNull HeightMap heightMap) {
            return worldInfo.getMinHeight();
        }

        @Override
        public boolean canSpawn(@NotNull World world, int x, int z) { return true; }

        @Override
        public @Nullable BiomeProvider getDefaultBiomeProvider(@NotNull WorldInfo worldInfo) {
            return VOID_BIOME_PROVIDER;
        }
    }

    public static class VanillaVoidBiomeProvider extends BiomeProvider {
        @Override
        public @NotNull Biome getBiome(@NotNull WorldInfo worldInfo, int x, int y, int z) {
            return Biome.THE_VOID;
        }

        @Override
        public @NotNull List<Biome> getBiomes(@NotNull WorldInfo worldInfo) {
            return List.of(Biome.THE_VOID);
        }
    }
}