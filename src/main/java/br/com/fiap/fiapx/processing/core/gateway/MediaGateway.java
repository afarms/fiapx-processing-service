package br.com.fiap.fiapx.processing.core.gateway;

import java.io.IOException;
import java.nio.file.Path;
import java.util.function.BooleanSupplier;

/** Input is a verified local download. The caller must persist beginMedia before invoking this port. */
public interface MediaGateway {
    LocalMediaResult extract(Path input, BooleanSupplier stillOwned) throws IOException;

    interface LocalMediaResult extends AutoCloseable {
        Path zip();
        long sizeBytes();
        String sha256();
        int frameCount();
        /** Release local files only after storage has completed or recovery responsibility was transferred. */
        @Override void close() throws IOException;
    }
}
