package br.com.fiap.fiapx.processing.core.gateway;

import br.com.fiap.fiapx.processing.core.domain.*;
import java.io.IOException;
import java.nio.file.Path;
import java.util.Optional;
import java.util.UUID;
import java.util.function.BiPredicate;
import java.util.function.BooleanSupplier;

public interface LocalArtifactsGateway {
    Path original(JobSnapshot job) throws IOException;
    void retain(ResultArtifact artifact, Path source, BooleanSupplier owned) throws IOException;
    Optional<Path> find(ResultArtifact artifact, BooleanSupplier owned) throws IOException;
    void remove(ResultArtifact artifact) throws IOException;
    void cleanup(BiPredicate<UUID, UUID> eligible) throws IOException;
}
