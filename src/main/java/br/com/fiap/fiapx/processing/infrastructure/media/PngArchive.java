package br.com.fiap.fiapx.processing.infrastructure.media;

import br.com.fiap.fiapx.processing.core.domain.*;
import java.io.*;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.zip.*;

/** Parses concatenated image2pipe PNGs with fixed-size buffers; never holds an image in memory. */
final class PngArchive {
    private static final byte[] SIGNATURE = {(byte)137,80,78,71,13,10,26,10};
    private final long maximum;
    private long extracted;
    private int frames;
    PngArchive(long maximum) { this.maximum = maximum; }

    void write(InputStream source, ZipOutputStream zip, ExecutionBudget budget) throws IOException {
        var input = new DataInputStream(new BufferedInputStream(source));
        byte[] buffer = new byte[8192];
        for (int first; (first = input.read()) != -1;) {
            budget.check();
            byte[] signature = new byte[8]; signature[0] = (byte)first;
            read(input, signature, 1, 7);
            if (!Arrays.equals(SIGNATURE, signature)) invalid();
            var entry = new ZipEntry("frame-%06d.png".formatted(++frames));
            entry.setTime(0); zip.putNextEntry(entry);
            append(zip, signature, 8);
            boolean header = false, data = false;
            while (true) {
                budget.check();
                byte[] chunk = new byte[8]; read(input, chunk, 0, 8);
                long length = Integer.toUnsignedLong(ByteBuffer.wrap(chunk).getInt());
                String type = new String(chunk, 4, 4, StandardCharsets.US_ASCII);
                if (!header && (!type.equals("IHDR") || length != 13)) invalid();
                header = true;
                if (length > maximum - extracted - 12) throw new MediaFailure(FailureCode.OUTPUT_LIMIT_EXCEEDED);
                append(zip, chunk, 8);
                var crc = new CRC32(); crc.update(chunk, 4, 4);
                for (long remaining = length; remaining > 0;) {
                    budget.check();
                    int size = (int)Math.min(buffer.length, remaining);
                    read(input, buffer, 0, size); crc.update(buffer, 0, size); append(zip, buffer, size);
                    remaining -= size;
                }
                byte[] checksum = new byte[4]; read(input, checksum, 0, 4);
                if (Integer.toUnsignedLong(ByteBuffer.wrap(checksum).getInt()) != crc.getValue()) invalid();
                append(zip, checksum, 4);
                data |= type.equals("IDAT");
                if (type.equals("IEND")) { if (length != 0 || !data) invalid(); break; }
            }
            zip.closeEntry();
        }
        if (frames == 0) invalid();
    }
    int frames() { return frames; }
    private void append(OutputStream target, byte[] bytes, int length) throws IOException {
        if (length > maximum - extracted) throw new MediaFailure(FailureCode.OUTPUT_LIMIT_EXCEEDED);
        extracted += length; target.write(bytes, 0, length);
    }
    private static void read(DataInputStream input, byte[] bytes, int offset, int length) throws IOException {
        try { input.readFully(bytes, offset, length); } catch (EOFException truncated) { invalid(); }
    }
    private static void invalid() { throw new MediaFailure(FailureCode.INVALID_MEDIA); }
}
