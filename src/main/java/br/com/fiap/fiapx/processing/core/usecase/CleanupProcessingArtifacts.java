package br.com.fiap.fiapx.processing.core.usecase;

import br.com.fiap.fiapx.processing.core.domain.ResultKey;
import br.com.fiap.fiapx.processing.core.gateway.*;
import java.io.IOException;

/** Explicit invocation only. Scheduling belongs to the recovery worker. */
public final class CleanupProcessingArtifacts {
    private final ArtifactCleanupGateway eligibility;
    private final ObjectStorageGateway objects;
    private final LocalArtifactsGateway files;
    public CleanupProcessingArtifacts(ArtifactCleanupGateway eligibility, ObjectStorageGateway objects, LocalArtifactsGateway files) {
        this.eligibility = eligibility; this.objects = objects; this.files = files;
    }
    public void run(int limit) throws IOException {
        for (var artifact : eligibility.abandoned(limit)) {
            var key = ResultKey.parse(artifact.objectKey());
            try {
                if (eligibility.remoteAllowed(key.job(), key.producer())) objects.deleteAbandoned(artifact);
            } finally { eligibility.checked(key.producer()); }
        }
        files.cleanup(eligibility::localAllowed);
    }
}
