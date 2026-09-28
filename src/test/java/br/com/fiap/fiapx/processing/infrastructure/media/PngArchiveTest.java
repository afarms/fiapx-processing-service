package br.com.fiap.fiapx.processing.infrastructure.media;

import br.com.fiap.fiapx.processing.core.domain.*;
import java.io.*;
import java.util.*;
import java.util.zip.*;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class PngArchiveTest {
    private static byte[] archive(byte[] png, long pngLimit, long zipLimit) throws IOException {
        var result = new ByteArrayOutputStream();
        try (var zip = new ZipOutputStream(new LimitedOutput(result, zipLimit))) {
            var archive = new PngArchive(pngLimit);
            archive.write(new ByteArrayInputStream(png), zip, MediaFixtures.budget());
            assertTrue(archive.frames() > 0);
        }
        return result.toByteArray();
    }
    @Test void countsEveryPngAndZipByteAndAcceptsExactBoundaries() throws Exception {
        byte[] png = MediaFixtures.png();
        byte[] two = new byte[png.length * 2];
        System.arraycopy(png, 0, two, 0, png.length); System.arraycopy(png, 0, two, png.length, png.length);
        byte[] zip = archive(two, two.length, 100000);
        assertArrayEquals(zip, archive(two, two.length, zip.length));
        try (var input = new ZipInputStream(new ByteArrayInputStream(zip))) {
            assertEquals("frame-000001.png", input.getNextEntry().getName()); assertArrayEquals(png, input.readAllBytes());
            assertEquals("frame-000002.png", input.getNextEntry().getName()); assertArrayEquals(png, input.readAllBytes());
            assertNull(input.getNextEntry());
        }
        assertEquals(FailureCode.OUTPUT_LIMIT_EXCEEDED, assertThrows(MediaFailure.class,
                () -> archive(two, two.length - 1, 100000)).code());
        assertEquals(FailureCode.OUTPUT_LIMIT_EXCEEDED, assertThrows(MediaFailure.class,
                () -> archive(two, two.length, zip.length - 1)).code());
        assertThrows(MediaFailure.class, () -> archive(png, 1, 100000));
    }
    @Test void rejectsTruncationCorruptionMissingImagesAndMalformedChunks() throws Exception {
        byte[] png = MediaFixtures.png();
        byte[] corrupt = png.clone(); corrupt[29] ^= 1;
        byte[] wrongFirst = png.clone(); wrongFirst[12] = 'X';
        byte[] wrongLength = png.clone(); wrongLength[11] = 12;
        byte[] noData = new byte[45];
        System.arraycopy(png, 0, noData, 0, 33); System.arraycopy(png, png.length - 12, noData, 33, 12);
        for (byte[] value : List.of(new byte[0], new byte[]{1}, new byte[8], corrupt, wrongFirst, wrongLength,
                noData, Arrays.copyOf(png, png.length - 1))) {
            assertEquals(FailureCode.INVALID_MEDIA, assertThrows(MediaFailure.class,
                    () -> archive(value, 100000, 100000)).code());
        }
    }
    @Test void outputStopsBeforeWritingBeyondQuota() throws Exception {
        var bytes = new ByteArrayOutputStream();
        var limited = new LimitedOutput(bytes, 2);
        limited.write(1); limited.write(new byte[]{2}, 0, 1);
        assertThrows(MediaFailure.class, () -> limited.write(3)); assertEquals(2, bytes.size());
    }
}
