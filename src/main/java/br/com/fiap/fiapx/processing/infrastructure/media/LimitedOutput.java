package br.com.fiap.fiapx.processing.infrastructure.media;

import br.com.fiap.fiapx.processing.core.domain.*;
import java.io.*;

/** Rejects before writing the byte that exceeds quota, including ZIP central-directory bytes. */
final class LimitedOutput extends FilterOutputStream {
    private final long maximum;
    private long count;
    LimitedOutput(OutputStream target, long maximum) { super(target); this.maximum = maximum; }
    @Override public void write(int value) throws IOException { reserve(1); out.write(value); }
    @Override public void write(byte[] value, int offset, int length) throws IOException {
        reserve(length); out.write(value, offset, length);
    }
    private void reserve(int length) {
        if (length > maximum - count) throw new MediaFailure(FailureCode.OUTPUT_LIMIT_EXCEEDED);
        count += length;
    }
}
