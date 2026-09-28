package br.com.fiap.fiapx.processing.core.gateway;

import br.com.fiap.fiapx.processing.core.domain.ResultArtifact;
import java.util.List;
import java.util.UUID;

public interface ArtifactCleanupGateway {
    List<ResultArtifact> abandoned(int limit);
    boolean remoteAllowed(UUID job, UUID producer);
    boolean localAllowed(UUID job, UUID producer);
    /** Rotate the scan, but keep intents eligible for repeated cleanup of late writes. */
    void checked(UUID producer);
}
