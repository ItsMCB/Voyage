package org.libregalaxy.voyage.core;

import org.bukkit.plugin.java.JavaPlugin;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.mock;

class WorldFileServiceZipSafetyTest {

    @TempDir
    Path tempDir;

    @Test
    void extractZipArchive_rejectsZipSlipTraversalEntry() throws Exception {
        Path zipPath = tempDir.resolve("malicious.zip");
        try (ZipOutputStream zos = new ZipOutputStream(Files.newOutputStream(zipPath))) {
            zos.putNextEntry(new ZipEntry("../../escaped.txt"));
            zos.write("pwned".getBytes(StandardCharsets.UTF_8));
            zos.closeEntry();
        }

        Path extractTarget = tempDir.resolve("extracted/world");
        WorldFileService fileService = new WorldFileService(mock(JavaPlugin.class));

        assertThrows(SecurityException.class,
                () -> fileService.extractZipArchive(zipPath, extractTarget),
                "Zip entries escaping the target directory must throw SecurityException");
        assertFalse(Files.exists(tempDir.resolve("escaped.txt")));
    }

    @Test
    void extractZipArchive_extractsValidEntriesSafely() throws Exception {
        Path zipPath = tempDir.resolve("valid.zip");
        try (ZipOutputStream zos = new ZipOutputStream(Files.newOutputStream(zipPath))) {
            zos.putNextEntry(new ZipEntry("region/r.0.0.mca"));
            zos.write("mca-bytes".getBytes(StandardCharsets.UTF_8));
            zos.closeEntry();
        }

        Path extractTarget = tempDir.resolve("extracted/valid_world");
        WorldFileService fileService = new WorldFileService(mock(JavaPlugin.class));

        fileService.extractZipArchive(zipPath, extractTarget);

        Path extractedFile = extractTarget.resolve("region/r.0.0.mca");
        assertTrue(Files.isRegularFile(extractedFile));
        assertEquals("mca-bytes", Files.readString(extractedFile, StandardCharsets.UTF_8));
    }
}