package org.libregalaxy.voyage.commands.world;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

import static org.junit.jupiter.api.Assertions.*;

class InfoScmdTest {

    // ─── Boundary Values ───

    @Test
    void formatFolderSizeString_zeroBytes() {
        assertEquals("0 bytes", InfoScmd.formatFolderSizeString(0L));
    }

    @Test
    void formatFolderSizeString_oneByte() {
        assertEquals("1 bytes", InfoScmd.formatFolderSizeString(1L));
    }

    @Test
    void formatFolderSizeString_twoBytes() {
        assertEquals("2 bytes", InfoScmd.formatFolderSizeString(2L));
    }

    @Test
    void formatFolderSizeString_justUnderKB() {
        assertEquals("999 bytes", InfoScmd.formatFolderSizeString(999L));
    }

    @Test
    void formatFolderSizeString_exactly1KB() {
        assertEquals("1.0 kB", InfoScmd.formatFolderSizeString(1000L));
    }

    @Test
    void formatFolderSizeString_justUnderMB() {
        assertEquals("999.9 kB", InfoScmd.formatFolderSizeString(999_900L));
    }

    @Test
    void formatFolderSizeString_exactly1MB() {
        assertEquals("1.0 MB", InfoScmd.formatFolderSizeString(1_000_000L));
    }

    @Test
    void formatFolderSizeString_exactly1GB() {
        assertEquals("1.0 GB", InfoScmd.formatFolderSizeString(1_000_000_000L));
    }

    @Test
    void formatFolderSizeString_exactly1TB() {
        assertEquals("1.0 TB", InfoScmd.formatFolderSizeString(1_000_000_000_000L));
    }

    @Test
    void formatFolderSizeString_exactly1PB() {
        assertEquals("1.0 PB", InfoScmd.formatFolderSizeString(1_000_000_000_000_000L));
    }

    @Test
    void formatFolderSizeString_exactly1EB() {
        assertEquals("1.0 EB", InfoScmd.formatFolderSizeString(1_000_000_000_000_000_000L));
    }

    // ─── Decimal Values ───

    @Test
    void formatFolderSizeString_decimalKB() {
        assertEquals("1.5 kB", InfoScmd.formatFolderSizeString(1500L));
        // Java's String.format uses RoundingMode.HALF_UP, so 1.25 rounds to 1.3
        assertEquals("1.3 kB", InfoScmd.formatFolderSizeString(1250L));
    }

    @Test
    void formatFolderSizeString_decimalMB() {
        assertEquals("2.5 MB", InfoScmd.formatFolderSizeString(2_500_000L));
        assertEquals("1.5 MB", InfoScmd.formatFolderSizeString(1_500_000L));
    }

    @Test
    void formatFolderSizeString_decimalGB() {
        assertEquals("1.5 GB", InfoScmd.formatFolderSizeString(1_500_000_000L));
        assertEquals("10.5 GB", InfoScmd.formatFolderSizeString(10_500_000_000L));
    }

    // ─── Parameterized ───

    @ParameterizedTest
    @CsvSource({
            "0, 0 bytes",
            "1, 1 bytes",
            "500, 500 bytes",
            "999, 999 bytes",
            "1000, 1.0 kB",
            "1500, 1.5 kB",
            "10000, 10.0 kB",
            "100000, 100.0 kB",
            "1000000, 1.0 MB",
            "2500000, 2.5 MB",
            "10000000, 10.0 MB",
            "100000000, 100.0 MB",
            "1000000000, 1.0 GB",
            "10500000000, 10.5 GB",
            "1000000000000, 1.0 TB",
            "1000000000000000, 1.0 PB",
            "1000000000000000000, 1.0 EB"
    })
    void formatFolderSizeString_parameterized(long input, String expected) {
        assertEquals(expected, InfoScmd.formatFolderSizeString(input));
    }

    // ─── Realistic World Sizes ───

    @Test
    void formatFolderSizeString_smallWorld() {
        assertEquals("50.0 MB", InfoScmd.formatFolderSizeString(50_000_000L));
    }

    @Test
    void formatFolderSizeString_largeWorld() {
        assertEquals("5.0 GB", InfoScmd.formatFolderSizeString(5_000_000_000L));
    }

    @Test
    void formatFolderSizeString_hugeWorld() {
        assertEquals("500.0 GB", InfoScmd.formatFolderSizeString(500_000_000_000L));
    }
}