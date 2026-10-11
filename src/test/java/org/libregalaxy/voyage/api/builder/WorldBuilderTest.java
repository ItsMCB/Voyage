package org.libregalaxy.voyage.api.builder;

import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.NullAndEmptySource;
import org.junit.jupiter.params.provider.ValueSource;

import static org.junit.jupiter.api.Assertions.*;

class WorldBuilderTest {

    @Nested
    class ParseSeed {

        @ParameterizedTest
        @NullAndEmptySource
        @ValueSource(strings = {"   ", "\t", "\n", " \t "})
        void blankOrNullSeedDoesNotThrow(String seed) {
            // Should produce a random long — we just verify no exception
            assertDoesNotThrow(() -> WorldBuilder.parseSeed(seed));
        }

        @ParameterizedTest
        @CsvSource({
                "0, 0",
                "1, 1",
                "-1, -1",
                "42, 42",
                "-99999, -99999",
                "9223372036854775807, 9223372036854775807",
                "-9223372036854775808, -9223372036854775808"
        })
        void numericLongParsedDirectly(String input, long expected) {
            assertEquals(expected, WorldBuilder.parseSeed(input));
        }

        @Test
        void positiveLongMaxValueParsedDirectly() {
            assertEquals(Long.MAX_VALUE, WorldBuilder.parseSeed(String.valueOf(Long.MAX_VALUE)));
        }

        @Test
        void negativeLongMinValueParsedDirectly() {
            assertEquals(Long.MIN_VALUE, WorldBuilder.parseSeed(String.valueOf(Long.MIN_VALUE)));
        }

        @ParameterizedTest
        @CsvSource({
                "hello",
                "Voyage",
                "world",
                "test_seed_123",
                "CustomWorld"
        })
        void nonNumericStringUsesHashCode(String input) {
            assertEquals(input.hashCode(), WorldBuilder.parseSeed(input));
        }

        @Test
        void numberTooLargeForLongFallsBackToHashCode() {
            String overflow = "99999999999999999999"; // exceeds Long.MAX_VALUE
            assertEquals(overflow.hashCode(), WorldBuilder.parseSeed(overflow));
        }

        @Test
        void decimalNumberFallsBackToHashCode() {
            // "3.14" is not a valid long — should use hashCode
            assertEquals("3.14".hashCode(), WorldBuilder.parseSeed("3.14"));
        }

        @Test
        void hexNumberFallsBackToHashCode() {
            // "0xFF" is not parsed as a long by Long.parseLong
            assertEquals("0xFF".hashCode(), WorldBuilder.parseSeed("0xFF"));
        }

        @Test
        void scientificNotationFallsBackToHashCode() {
            assertEquals("1e10".hashCode(), WorldBuilder.parseSeed("1e10"));
        }

        @Test
        void plusPrefixedNumberNotParsedAsLong() {
            // Long.parseLong("+42") actually works in Java, so verify consistency
            long result = WorldBuilder.parseSeed("+42");
            // Long.parseLong("+42") returns 42 in Java
            assertEquals(42L, result);
        }

        @Test
        void whitespaceOnlyStringTreatedAsBlank() {
            // Should not throw
            assertDoesNotThrow(() -> WorldBuilder.parseSeed("   "));
        }

        @Test
        void emptyStringDoesNotThrow() {
            assertDoesNotThrow(() -> WorldBuilder.parseSeed(""));
        }

        @Test
        void nullDoesNotThrow() {
            assertDoesNotThrow(() -> WorldBuilder.parseSeed(null));
        }

        @Test
        void twoRandomSeedsAreNotAlwaysEqual() {
            // Statistical test: generating 100 random seeds, at least some should differ
            boolean anyDifferent = false;
            long first = WorldBuilder.parseSeed(null);
            for (int i = 0; i < 100; i++) {
                if (WorldBuilder.parseSeed(null) != first) {
                    anyDifferent = true;
                    break;
                }
            }
            assertTrue(anyDifferent, "Random seeds should produce different values across calls");
        }
    }
}