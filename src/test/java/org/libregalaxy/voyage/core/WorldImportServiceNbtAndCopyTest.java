package org.libregalaxy.voyage.core;

import net.kyori.adventure.nbt.BinaryTag;
import net.kyori.adventure.nbt.ByteArrayBinaryTag;
import net.kyori.adventure.nbt.CompoundBinaryTag;
import net.kyori.adventure.nbt.DoubleBinaryTag;
import net.kyori.adventure.nbt.IntArrayBinaryTag;
import net.kyori.adventure.nbt.IntBinaryTag;
import net.kyori.adventure.nbt.ListBinaryTag;
import net.kyori.adventure.nbt.LongArrayBinaryTag;
import net.kyori.adventure.nbt.LongBinaryTag;
import net.kyori.adventure.nbt.StringBinaryTag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.lang.reflect.Method;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.*;

class WorldImportServiceNbtAndCopyTest {
    @TempDir Path tempDir;

    private String nbtToJson(BinaryTag tag) throws Exception {
        WorldImportService service = new WorldImportService(null, null);
        Method method = WorldImportService.class.getDeclaredMethod("nbtToJson", BinaryTag.class);
        method.setAccessible(true);
        return (String) method.invoke(service, tag);
    }

    @Test
    void nbtToJson_serializesPrimitiveNumericTags() throws Exception {
        assertEquals("42", nbtToJson(IntBinaryTag.intBinaryTag(42)));
        assertEquals("9223372036854775807", nbtToJson(LongBinaryTag.longBinaryTag(Long.MAX_VALUE)));
        assertEquals("3.5", nbtToJson(DoubleBinaryTag.doubleBinaryTag(3.5)));
    }

    @Test
    void nbtToJson_serializesByteAndIntArrays() throws Exception {
        assertEquals("[1,-2,3]", nbtToJson(ByteArrayBinaryTag.byteArrayBinaryTag(new byte[]{1, -2, 3})));
        assertEquals("[4,5,6]", nbtToJson(IntArrayBinaryTag.intArrayBinaryTag(new int[]{4, 5, 6})));
    }

    @Test
    void nbtToJson_serializesLongArrays() throws Exception {
        assertEquals("[4,5,9223372036854775807]",
                nbtToJson(LongArrayBinaryTag.longArrayBinaryTag(new long[]{4, 5, Long.MAX_VALUE})));
    }

    @Test
    void nbtToJson_serializesNestedHomogeneousListsAndCompounds() throws Exception {
        // NBT lists are homogeneous. The previous test constructed an invalid
        // String/Int mixed list, which correctly throws IllegalArgumentException.
        ListBinaryTag list = ListBinaryTag.builder()
                .add(StringBinaryTag.stringBinaryTag("one"))
                .add(StringBinaryTag.stringBinaryTag("two"))
                .build();

        CompoundBinaryTag root = CompoundBinaryTag.builder()
                .put("list", list)
                .put("nested", CompoundBinaryTag.builder()
                        .put("value", StringBinaryTag.stringBinaryTag("ok"))
                        .build())
                .build();

        assertEquals("{\"list\":[\"one\",\"two\"],\"nested\":{\"value\":\"ok\"}}", nbtToJson(root));
    }

    @Test
    void nbtToJson_escapesBackslashQuoteAndControlCharacters() throws Exception {
        String input = "a\\b\"c\n\r\t";
        assertEquals("\"a\\\\b\\\"c\\n\\r\\t\"",
                nbtToJson(StringBinaryTag.stringBinaryTag(input)));
    }

    @Test
    void copyDirectoryRecursively_excludesSessionUidAndPaperMetadataFiles() throws Exception {
        Path source = tempDir.resolve("source");
        Path target = tempDir.resolve("target");
        Files.createDirectories(source.resolve("region"));
        Files.createDirectories(source.resolve("data/paper"));
        Files.writeString(source.resolve("region/r.0.0.mca"), "region");
        Files.writeString(source.resolve("uid.dat"), "uid");
        Files.writeString(source.resolve("session.lock"), "lock");
        Files.writeString(source.resolve("data/paper/metadata.dat"), "metadata");
        Files.writeString(source.resolve("data/keep.dat"), "keep");

        WorldImportService service = new WorldImportService(null, null);
        Method method = WorldImportService.class.getDeclaredMethod("copyDirectoryRecursively", Path.class, Path.class);
        method.setAccessible(true);
        method.invoke(service, source, target);

        assertTrue(Files.exists(target.resolve("region/r.0.0.mca")));
        assertTrue(Files.exists(target.resolve("data/keep.dat")));
        assertFalse(Files.exists(target.resolve("uid.dat")));
        assertFalse(Files.exists(target.resolve("session.lock")));
        assertFalse(Files.exists(target.resolve("data/paper/metadata.dat")));
    }
}
