package br.com.fiap.fiapx.processing.core.domain;

import static org.junit.jupiter.api.Assertions.*;
import java.util.stream.Stream;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;

class ProcessingLimitsTest {
    private static long[] defaults() {
        return new long[]{100_000_000, 300, 1_073_741_824, 1_073_741_824, 600, 3, 3_221_225_472L, 120, 30};
    }

    private static ProcessingLimits limits(long[] v) {
        return new ProcessingLimits(v[0], v[1], v[2], v[3], v[4], v[5], v[6], v[7], v[8]);
    }

    static Stream<Arguments> nonPositiveLimits() {
        return java.util.stream.IntStream.range(0, 9).boxed()
                .flatMap(index -> Stream.of(Arguments.of(index, 0L), Arguments.of(index, -1L)));
    }

    @ParameterizedTest
    @MethodSource("nonPositiveLimits")
    void rejectsDisabledOrNegativeResourceLimits(int index, long invalid) {
        long[] values = defaults();
        values[index] = invalid;
        assertThrows(IllegalArgumentException.class, () -> limits(values));
    }

    @Test
    void acceptsAgreedLimitsWithDiskHeadroom() {
        ProcessingLimits result = limits(defaults());
        assertEquals(100_000_000, result.maxInputBytes());
        assertEquals(300, result.maxDurationSeconds());
        assertEquals(1_073_741_824, result.maxExtractedBytes());
        assertEquals(1_073_741_824, result.maxZipBytes());
        assertEquals(600, result.timeoutSeconds());
        assertEquals(3, result.maxAttempts());
        assertEquals(3_221_225_472L, result.diskReserveBytes());
        assertEquals(120, result.leaseSeconds());
        assertEquals(30, result.heartbeatSeconds());
    }

    @Test
    void rejectsHeartbeatAtOrAfterLeaseExpiry() {
        for (long heartbeat : new long[]{120, 121}) {
            long[] values = defaults();
            values[8] = heartbeat;
            assertThrows(IllegalArgumentException.class, () -> limits(values));
        }
    }

    @Test
    void reservesInputImagesAndZipAtTheSameTimeWithAdditionalSpace() {
        long[] values = defaults();
        long minimum = values[0] + values[2] + values[3];
        values[6] = minimum;
        assertThrows(IllegalArgumentException.class, () -> limits(values));
        values[6] = minimum - 1;
        assertThrows(IllegalArgumentException.class, () -> limits(values));
        values[6] = minimum + 1;
        assertDoesNotThrow(() -> limits(values));
    }

    @Test
    void rejectsOverflowInsteadOfAcceptingAnInsufficientReservation() {
        long[] values = defaults();
        values[2] = Long.MAX_VALUE;
        assertThrows(ArithmeticException.class, () -> limits(values));
        long[] inputOverflow = defaults();
        inputOverflow[0] = Long.MAX_VALUE;
        assertThrows(ArithmeticException.class, () -> limits(inputOverflow));
    }
}
