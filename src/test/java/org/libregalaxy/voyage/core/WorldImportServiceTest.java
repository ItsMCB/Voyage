package org.libregalaxy.voyage.core;

import net.kyori.adventure.nbt.*;
import org.bukkit.NamespacedKey;
import org.bukkit.World;
import org.bukkit.WorldType;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.libregalaxy.voyage.api.VoyageAPI;
import org.libregalaxy.voyage.core.migration.LevelDatNbt;
import org.libregalaxy.voyage.core.migration.VanillaPlayerData;
import org.libregalaxy.voyage.core.migration.VanillaWorldExporter;

import java.lang.reflect.Method;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.UUID;
import java.util.logging.Logger;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;

import static org.junit.jupiter.api.Assertions.*;

class WorldImportServiceTest {

    @TempDir
    Path tempDir;

    @Test
    void nbtToJson_escapesNewlinesInStrings() throws Exception {
        WorldImportService service = new WorldImportService(null, null);
        Method method = WorldImportService.class.getDeclaredMethod("nbtToJson", BinaryTag.class);
        method.setAccessible(true);

        StringBinaryTag tag = StringBinaryTag.stringBinaryTag("line1\nline2");
        String json = (String) method.invoke(service, tag);

        assertTrue(json.contains("\\n"), "Newline should be escaped as \\n");
        assertFalse(json.matches(".*\".*\n.*\".*"), "Raw newline should not be present in JSON string");
    }

    @Test
    void nbtToJson_escapesQuotesInStrings() throws Exception {
        WorldImportService service = new WorldImportService(null, null);
        Method method = WorldImportService.class.getDeclaredMethod("nbtToJson", BinaryTag.class);
        method.setAccessible(true);

        StringBinaryTag tag = StringBinaryTag.stringBinaryTag("val\"ue");
        String json = (String) method.invoke(service, tag);

        assertTrue(json.contains("\\\""), "Quote should be escaped as \\\"");
        assertEquals("\"val\\\"ue\"", json);
    }

    @Test
    void nbtToJson_escapesCompoundKeysWithQuotes() throws Exception {
        WorldImportService service = new WorldImportService(null, null);
        Method method = WorldImportService.class.getDeclaredMethod("nbtToJson", BinaryTag.class);
        method.setAccessible(true);

        CompoundBinaryTag compound = CompoundBinaryTag.builder()
                .put("key\"WithQuote", StringBinaryTag.stringBinaryTag("val"))
                .build();
        String json = (String) method.invoke(service, compound);

        assertTrue(json.contains("\"key\\\"WithQuote\":"), "Compound key with quote should be escaped");
    }

    @Test
    void serializeFlatSettingsToJson_convertsIntegerFlagsToBooleans() {
        CompoundBinaryTag flatSettings = CompoundBinaryTag.builder()
                .put("layers", ListBinaryTag.builder(BinaryTagTypes.COMPOUND)
                        .add(CompoundBinaryTag.builder().putString("block", "minecraft:bedrock").putInt("height", 1).build())
                        .add(CompoundBinaryTag.builder().putString("block", "minecraft:dirt").putInt("height", 2).build())
                        .add(CompoundBinaryTag.builder().putString("block", "minecraft:grass_block").putInt("height", 1).build())
                        .build())
                .putString("biome", "minecraft:plains")
                .putByte("features", (byte) 0)
                .putByte("lakes", (byte) 0)
                .build();

        String json = WorldImportService.serializeFlatSettingsToJson(flatSettings);
        assertNotNull(json);
        assertTrue(json.contains("\"features\":false"));
        assertTrue(json.contains("\"lakes\":false"));
        assertTrue(json.contains("\"biome\":\"minecraft:plains\""));
        assertTrue(json.contains("\"block\":\"minecraft:bedrock\""));
    }

    @Test
    void parseWorldData_extracts1_21_11_superflat_correctly() throws Exception {
        CompoundBinaryTag overworldGen = CompoundBinaryTag.builder()
                .putString("type", "minecraft:flat")
                .put("settings", CompoundBinaryTag.builder()
                        .put("layers", ListBinaryTag.builder(BinaryTagTypes.COMPOUND)
                                .add(CompoundBinaryTag.builder().putString("block", "minecraft:bedrock").putInt("height", 1).build())
                                .add(CompoundBinaryTag.builder().putString("block", "minecraft:dirt").putInt("height", 2).build())
                                .add(CompoundBinaryTag.builder().putString("block", "minecraft:grass_block").putInt("height", 1).build())
                                .build())
                        .putByte("features", (byte) 0)
                        .putByte("lakes", (byte) 0)
                        .putString("biome", "minecraft:plains")
                        .build())
                .build();

        CompoundBinaryTag overworldDim = CompoundBinaryTag.builder()
                .putString("type", "minecraft:overworld")
                .put("generator", overworldGen)
                .build();

        CompoundBinaryTag worldGenSettings = CompoundBinaryTag.builder()
                .putLong("seed", -196543795878308472L)
                .putByte("generate_features", (byte) 0)
                .put("dimensions", CompoundBinaryTag.builder().put("minecraft:overworld", overworldDim).build())
                .build();

        CompoundBinaryTag levelData = CompoundBinaryTag.builder()
                .putInt("DataVersion", 4671)
                .put("WorldGenSettings", worldGenSettings)
                .build();

        CompoundBinaryTag root = CompoundBinaryTag.builder().put("Data", levelData).build();

        Path levelDat = tempDir.resolve("level.dat");
        BinaryTagIO.writer().write(root, levelDat, BinaryTagIO.Compression.GZIP);

        WorldImportService service = new WorldImportService(null, null);
        WorldImportService.ParsedWorldData parsed = service.parseWorldData(tempDir, World.Environment.NORMAL);

        assertEquals(-196543795878308472L, parsed.globalSeed());
        assertFalse(parsed.globalGenerateStructures());
        assertEquals(4671, parsed.dataVersion());

        WorldImportService.DimensionGenInfo dimInfo = service.resolveDimensionInfo(parsed, "minecraft:overworld", World.Environment.NORMAL);
        assertEquals(WorldType.FLAT, dimInfo.worldType());
        assertFalse(dimInfo.generateStructures());
        assertNotNull(dimInfo.generatorSettingsJson());
        assertTrue(dimInfo.generatorSettingsJson().contains("\"biome\":\"minecraft:plains\""));
    }

    @Test
    void parseWorldData_superflatWithTheVoidBiomeAndBlocks_preservesBlockLayers() throws Exception {
        CompoundBinaryTag overworldGen = CompoundBinaryTag.builder()
                .putString("type", "minecraft:flat")
                .put("settings", CompoundBinaryTag.builder()
                        .put("layers", ListBinaryTag.builder(BinaryTagTypes.COMPOUND)
                                .add(CompoundBinaryTag.builder().putString("block", "minecraft:bedrock").putInt("height", 1).build())
                                .add(CompoundBinaryTag.builder().putString("block", "minecraft:stone").putInt("height", 3).build())
                                .build())
                        .putByte("features", (byte) 0)
                        .putByte("lakes", (byte) 0)
                        .putString("biome", "minecraft:the_void")
                        .build())
                .build();

        CompoundBinaryTag overworldDim = CompoundBinaryTag.builder()
                .putString("type", "minecraft:overworld")
                .put("generator", overworldGen)
                .build();

        CompoundBinaryTag worldGenSettings = CompoundBinaryTag.builder()
                .putLong("seed", 42L)
                .putByte("generate_features", (byte) 0)
                .put("dimensions", CompoundBinaryTag.builder().put("planet:template", overworldDim).build())
                .build();

        CompoundBinaryTag levelData = CompoundBinaryTag.builder()
                .putInt("DataVersion", 4671)
                .put("WorldGenSettings", worldGenSettings)
                .build();

        CompoundBinaryTag root = CompoundBinaryTag.builder().put("Data", levelData).build();

        Path levelDat = tempDir.resolve("level.dat");
        BinaryTagIO.writer().write(root, levelDat, BinaryTagIO.Compression.GZIP);

        WorldImportService service = new WorldImportService(null, null);
        WorldImportService.ParsedWorldData parsed = service.parseWorldData(tempDir, World.Environment.NORMAL);
        WorldImportService.DimensionGenInfo dimInfo = service.resolveDimensionInfo(parsed, "planet:template", World.Environment.NORMAL);

        assertEquals(WorldType.FLAT, dimInfo.worldType());
        assertNull(dimInfo.generatorPluginName(), "Superflat with non-air layers and the_void biome must not be forced to Voyage:void generator");
        assertNotNull(dimInfo.generatorSettingsJson());
        assertTrue(dimInfo.generatorSettingsJson().contains("minecraft:stone"), "Stone layer must be preserved");
        assertFalse(VoyageAPI.isVoidWorld(dimInfo.generatorPluginName(), dimInfo.generatorSettingsJson()));
    }

    @Test
    void export_customNamespaceWorldWithTheVoidSuperflat_setsPlayerDimensionToOverworldAndPreservesLayers() throws Exception {
        Path worldDir = tempDir.resolve("planet_template");
        Files.createDirectories(worldDir.resolve("region"));
        Files.writeString(worldDir.resolve("region/r.0.0.mca"), "dummy-mca-content");

        String flatJson = "{\"layers\":[{\"block\":\"minecraft:bedrock\",\"height\":1},{\"block\":\"minecraft:sandstone\",\"height\":4}],\"biome\":\"minecraft:the_void\",\"features\":false,\"lakes\":false}";

        WorldImportService service = new WorldImportService(null, null);
        WorldImportService.DimensionGenInfo genInfo = new WorldImportService.DimensionGenInfo(
                WorldType.FLAT, flatJson, null, World.Environment.NORMAL, 999L, false
        );
        CompoundBinaryTag rawData = CompoundBinaryTag.builder()
                .put("spawn", CompoundBinaryTag.builder()
                        .putIntArray("pos", new int[]{10, 65, -20})
                        .putString("dimension", "planet:template")
                        .build())
                .build();
        WorldImportService.ParsedWorldData parsed = new WorldImportService.ParsedWorldData(
                rawData, 4671, 999L, false, java.util.Map.of("planet:template", genInfo)
        );

        service.writeDimensionGenFiles(worldDir, NamespacedKey.fromString("planet:template"), genInfo, parsed);

        UUID playerUuid = UUID.randomUUID();
        VanillaPlayerData.ExportedPlayer exportedPlayer = new VanillaPlayerData.ExportedPlayer(
                playerUuid, "Builder", CompoundBinaryTag.empty(),
                new VanillaPlayerData.LastPosition(12.5, 66.0, -18.5, 90f, 0f)
        );

        Path targetZip = tempDir.resolve("exported.zip");
        new VanillaWorldExporter(Logger.getLogger("Test")).export(
                worldDir, "planet_template", "Planet Template", false,
                List.of(exportedPlayer), playerUuid,
                new VanillaWorldExporter.WorldBorderData(0, 0, 1024.0, 0.2, 5.0, 5, 15),
                targetZip
        );

        assertTrue(Files.exists(targetZip));

        try (ZipFile zf = new ZipFile(targetZip.toFile())) {
            ZipEntry levelEntry = zf.getEntry("planet_template/level.dat");
            assertNotNull(levelEntry);
            Path extractedLevel = tempDir.resolve("extracted_level.dat");
            try (var in = zf.getInputStream(levelEntry)) {
                Files.copy(in, extractedLevel);
            }

            CompoundBinaryTag exportedRoot = LevelDatNbt.read(extractedLevel);
            CompoundBinaryTag data = LevelDatNbt.getData(exportedRoot);

            // Player Dimension must be minecraft:overworld, NOT planet:template
            CompoundBinaryTag playerTag = data.getCompound("Player");
            assertEquals("minecraft:overworld", playerTag.getString("Dimension"));
            assertEquals("minecraft:overworld", playerTag.getString("dimension"));

            // Spawn compound must be normalized to minecraft:overworld
            assertEquals(10, data.getInt("SpawnX"));
            assertEquals(65, data.getInt("SpawnY"));
            assertEquals(-20, data.getInt("SpawnZ"));
            assertEquals("minecraft:overworld", data.getCompound("spawn").getString("dimension"));

            // WorldGenSettings must have minecraft:overworld with sandstone layer preserved
            CompoundBinaryTag overworldDim = data.getCompound("WorldGenSettings")
                    .getCompound("dimensions")
                    .getCompound("minecraft:overworld");
            String exportedSettings = WorldImportService.serializeFlatSettingsToJson(
                    overworldDim.getCompound("generator").get("settings"));
            assertNotNull(exportedSettings);
            assertTrue(exportedSettings.contains("minecraft:sandstone"));

            // Region files must be packaged exclusively under dimensions/minecraft/overworld/region
            assertNotNull(zf.getEntry("planet_template/dimensions/minecraft/overworld/region/r.0.0.mca"),
                    "Region file must exist under dimensions/minecraft/overworld/region/");
            assertNull(zf.getEntry("planet_template/region/r.0.0.mca"),
                    "Region file must NOT exist under root region/ to prevent FileAlreadyExistsException during GroupMove");

            // Modern namespaced border file must exist
            assertNotNull(zf.getEntry("planet_template/data/minecraft/world_border.dat"),
                    "data/minecraft/world_border.dat must exist in exported zip");

            // Legacy un-namespaced border file must NOT exist to prevent FileAlreadyExistsException in FileFixerUpper
            assertNull(zf.getEntry("planet_template/data/world_border.dat"),
                    "data/world_border.dat must NOT exist in exported zip");
            assertNull(zf.getEntry("planet_template/dimensions/minecraft/overworld/data/world_border.dat"),
                    "dimensions/minecraft/overworld/data/world_border.dat must NOT exist in exported zip");
        }
    }

    @Test
    void export_legacyWorldWithOnDiskWorldBorder_doesNotExportLegacyBorderFile() throws Exception {
        Path worldDir = tempDir.resolve("legacy_world");
        Files.createDirectories(worldDir.resolve("region"));
        Files.writeString(worldDir.resolve("region/r.0.0.mca"), "dummy-mca-content");

        // Simulate JE2BE legacy world border on disk under data/
        Path dataDir = worldDir.resolve("data");
        Files.createDirectories(dataDir);
        CompoundBinaryTag legacyBorderTag = CompoundBinaryTag.builder()
                .put("data", CompoundBinaryTag.builder()
                        .putDouble("center_x", 0.0)
                        .putDouble("center_z", 0.0)
                        .putDouble("size", 1024.0)
                        .putDouble("damage_per_block", 0.2)
                        .putDouble("safe_zone", 5.0)
                        .putInt("warning_blocks", 5)
                        .putInt("warning_time", 15)
                        .build())
                .putInt("DataVersion", 1343)
                .build();
        BinaryTagIO.writer().write(legacyBorderTag, dataDir.resolve("world_border.dat"), BinaryTagIO.Compression.GZIP);

        // Simulate legacy 1.12.2 level.dat containing old Version compound and requiresConversion flag
        CompoundBinaryTag legacyVersion = CompoundBinaryTag.builder()
                .putInt("Id", 1343)
                .putString("Name", "1.12.2")
                .putString("Series", "main")
                .putByte("Snapshot", (byte) 0)
                .build();

        CompoundBinaryTag levelData = CompoundBinaryTag.builder()
                .putInt("DataVersion", 1343)
                .putDouble("BorderSize", 1024.0)
                .putByte("requiresConversion", (byte) 1)
                .put("Version", legacyVersion)
                .build();
        CompoundBinaryTag root = CompoundBinaryTag.builder().put("Data", levelData).build();
        BinaryTagIO.writer().write(root, worldDir.resolve("level.dat"), BinaryTagIO.Compression.GZIP);

        Path targetZip = tempDir.resolve("exported_legacy.zip");
        new VanillaWorldExporter(Logger.getLogger("Test")).export(
                worldDir, "legacy_world", false, targetZip
        );

        assertTrue(Files.exists(targetZip));

        try (ZipFile zf = new ZipFile(targetZip.toFile())) {
            // Modern namespaced border file MUST exist
            assertNotNull(zf.getEntry("legacy_world/data/minecraft/world_border.dat"),
                    "data/minecraft/world_border.dat must exist in exported zip");

            // Legacy un-namespaced border file MUST NOT exist anywhere in the zip
            assertNull(zf.getEntry("legacy_world/data/world_border.dat"),
                    "data/world_border.dat must NOT exist in exported zip to prevent FileAlreadyExistsException in Minecraft FileFixerUpper");
            assertNull(zf.getEntry("legacy_world/dimensions/minecraft/overworld/data/world_border.dat"),
                    "dimensions/minecraft/overworld/data/world_border.dat must NOT exist in exported zip");

            // Region file MUST exist under dimensions/minecraft/overworld/region
            assertNotNull(zf.getEntry("legacy_world/dimensions/minecraft/overworld/region/r.0.0.mca"),
                    "dimensions/minecraft/overworld/region/r.0.0.mca must exist in exported zip");

            // Root region folder MUST NOT exist to prevent FileAlreadyExistsException in GroupMove
            assertNull(zf.getEntry("legacy_world/region/r.0.0.mca"),
                    "legacy_world/region/r.0.0.mca must NOT exist in root of exported zip");

            // DataVersion in level.dat must be updated to at least the server's version
            ZipEntry levelEntry = zf.getEntry("legacy_world/level.dat");
            assertNotNull(levelEntry);
            Path extractedLevel = tempDir.resolve("extracted_legacy_level.dat");
            try (var in = zf.getInputStream(levelEntry)) {
                Files.copy(in, extractedLevel);
            }
            CompoundBinaryTag exportedRoot = LevelDatNbt.read(extractedLevel);
            int exportedVersion = LevelDatNbt.resolveDataVersion(exportedRoot, 0);
            int serverVersion = LevelDatNbt.serverDataVersion();
            assertTrue(exportedVersion >= serverVersion,
                    "Exported DataVersion (" + exportedVersion + ") must be >= serverDataVersion (" + serverVersion + ")");

            CompoundBinaryTag exportedData = LevelDatNbt.getData(exportedRoot);

            // Data.Version.Id must be modernized to prevent "this world needs to be upgraded" screen
            assertTrue(exportedData.keySet().contains("Version"), "Data.Version must be present");
            CompoundBinaryTag versionComp = exportedData.getCompound("Version");
            assertEquals(exportedVersion, versionComp.getInt("Id"),
                    "Version.Id must match exported DataVersion");
            assertNotEquals("1.12.2", versionComp.getString("Name"),
                    "Version.Name must not retain the legacy 1.12.2 label");
            assertFalse(exportedData.keySet().contains("requiresConversion"),
                    "requiresConversion flag must be stripped");
        }
    }

    @Test
    void parseWorldData_withVoyageVoidGenerator_normalizesToVanillaVoid() {
        WorldImportService service = new WorldImportService(null, null);
        WorldImportService.ParsedWorldData parsed = service.parseWorldData(tempDir, World.Environment.NORMAL, "Voyage:void", null);

        WorldImportService.DimensionGenInfo dimInfo = service.resolveDimensionInfo(parsed, "minecraft:overworld", World.Environment.NORMAL);
        assertEquals(WorldType.FLAT, dimInfo.worldType());
        assertFalse(dimInfo.generateStructures());
        assertEquals(VoyageAPI.VANILLA_VOID_SETTINGS, dimInfo.generatorSettingsJson());
    }

    @Test
    void writeDimensionGenFiles_writesBothFilesCorrectly() throws Exception {
        Path dimDir = tempDir.resolve("dimensions/custom/test_flat");
        Files.createDirectories(dimDir);

        WorldImportService service = new WorldImportService(null, null);
        String json = "{\"layers\":[{\"block\":\"minecraft:bedrock\",\"height\":1}],\"biome\":\"minecraft:plains\",\"features\":false,\"lakes\":false}";

        WorldImportService.DimensionGenInfo genInfo = new WorldImportService.DimensionGenInfo(
                WorldType.FLAT,
                json,
                null,
                World.Environment.NORMAL,
                12345L,
                false
        );

        WorldImportService.ParsedWorldData data = new WorldImportService.ParsedWorldData(
                null,
                4671,
                12345L,
                false,
                java.util.Map.of("custom:test_flat", genInfo)
        );

        service.writeDimensionGenFiles(dimDir, NamespacedKey.fromString("custom:test_flat"), genInfo, data);

        Path wgsPath = dimDir.resolve("data/minecraft/world_gen_settings.dat");
        Path levelDatPath = dimDir.resolve("level.dat");

        assertTrue(Files.exists(wgsPath), "world_gen_settings.dat must be created");
        assertTrue(Files.exists(levelDatPath), "level.dat must be created");

        CompoundBinaryTag wgsRoot = BinaryTagIO.reader().read(wgsPath, BinaryTagIO.Compression.GZIP);
        CompoundBinaryTag wgsData = wgsRoot.getCompound("data");
        assertEquals(12345L, wgsData.getLong("seed"));
        assertEquals((byte) 0, wgsData.getByte("generate_structures"));

        CompoundBinaryTag levelRoot = BinaryTagIO.reader().read(levelDatPath, BinaryTagIO.Compression.GZIP);
        CompoundBinaryTag levelData = levelRoot.getCompound("Data");
        CompoundBinaryTag wgsFromLevel = levelData.getCompound("WorldGenSettings");
        assertEquals(12345L, wgsFromLevel.getLong("seed"));
        assertEquals((byte) 0, wgsFromLevel.getByte("generate_structures"));
    }
}