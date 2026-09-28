package br.com.fiap.fiapx.processing.core.gateway;

import br.com.fiap.fiapx.processing.core.domain.*;
import java.io.IOException;
import java.nio.file.Path;
import java.util.function.BooleanSupplier;

public interface ObjectStorageGateway {
    void download(ProcessingRequest request, Path target, BooleanSupplier owned) throws IOException;
    /** False only for a confirmed missing key; denial, mismatch and uncertainty must throw. */
    boolean present(ResultArtifact artifact, BooleanSupplier owned) throws IOException;
    /** Conditional immutable write followed by integrity confirmation; never overwrites. */
    void store(ResultArtifact artifact, Path file, BooleanSupplier owned) throws IOException;
    /** Only call after durable eligibility excludes active and referenced results. */
    void deleteAbandoned(ResultArtifact artifact) throws IOException;
}
