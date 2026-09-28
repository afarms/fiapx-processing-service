package br.com.fiap.fiapx.processing.infrastructure.messaging;

import br.com.fiap.fiapx.processing.core.usecase.CleanupProcessingArtifacts;
import java.io.IOException;
import org.springframework.scheduling.annotation.Scheduled;

public final class ArtifactMaintenance {
    private static final org.slf4j.Logger LOG=org.slf4j.LoggerFactory.getLogger(ArtifactMaintenance.class);
    private final CleanupProcessingArtifacts cleanup;
    public ArtifactMaintenance(CleanupProcessingArtifacts cleanup) { this.cleanup=cleanup; }
    @Scheduled(fixedDelayString="${messaging.cleanup-delay-ms:60000}")
    public void run() {
        try { cleanup.run(20); }
        catch (IOException | RuntimeException failure) { LOG.warn("Artifact cleanup deferred"); }
    }
}
