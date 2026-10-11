package org.libregalaxy.voyage.commands.world;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

import java.lang.reflect.Method;

import static org.junit.jupiter.api.Assertions.*;

class BorderScmdFormatSizeTest {

    private static String invokeFormatSize(double size) throws Exception {
        Method method = BorderScmd.class.getDeclaredMethod("formatSize", double.class);
        method.setAccessible(true);
        return (String) method.invoke(null, size);
    }

    @Test
    void integerSize_noDecimalPoint() throws Exception {
        assertEquals("100", invokeFormatSize(100.0));
        assertEquals("1", invokeFormatSize(1.0));
        assertEquals("60000000", invokeFormatSize(6.0E7));
    }

    @Test
    void maxBorderSize_formattedAsInteger() throws Exception {
        assertEquals("59999968", invokeFormatSize(5.9999968E7));
    }

    @Test
    void decimalSize_showsDecimal() throws Exception {
        assertEquals("100.5", invokeFormatSize(100.5));
        assertEquals("0.5", invokeFormatSize(0.5));
    }

    @Test
    void zeroSize_formattedAsInteger() throws Exception {
        assertEquals("0", invokeFormatSize(0.0));
    }

    @Test
    void negativeSize_showsDecimal() throws Exception {
        // %.0f drops the decimal point even for negative whole numbers
        assertEquals("-1", invokeFormatSize(-1.0));
        assertEquals("-100.5", invokeFormatSize(-100.5));
    }

    @Test
    void veryLargeSize_formattedAsInteger() throws Exception {
        assertEquals("1000000", invokeFormatSize(1000000.0));
    }

    @ParameterizedTest
    @CsvSource({
            "0.0, 0",
            "1.0, 1",
            "100.0, 100",
            "1000.0, 1000",
            "1000000.0, 1000000",
            "0.5, 0.5",
            "1.5, 1.5",
            "99.99, 99.99"
    })
    void formatSize_parameterized(double input, String expected) throws Exception {
        assertEquals(expected, invokeFormatSize(input));
    }

    @Test
    void formatSize_infinityNotFormattedAsInteger() throws Exception {
        String result = invokeFormatSize(Double.POSITIVE_INFINITY);
        assertEquals("Infinity", result);
    }

    @Test
    void formatSize_nanNotFormattedAsInteger() throws Exception {
        String result = invokeFormatSize(Double.NaN);
        assertEquals("NaN", result);
    }
}