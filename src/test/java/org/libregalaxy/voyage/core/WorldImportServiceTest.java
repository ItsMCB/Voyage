package org.libregalaxy.voyage.core;

import net.kyori.adventure.nbt.*;
import org.bukkit.NamespacedKey;
import org.bukkit.World;
import org.bukkit.WorldType;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.libregalaxy.voyage.api.VoyageAPI;

import java.lang.reflect.Method;
import java.nio.file.Files;
import java.nio.file.Path;

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