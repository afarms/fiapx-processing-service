package br.com.fiap.fiapx.processing;

import br.com.fiap.fiapx.processing.core.domain.*;
import java.time.Instant;
import java.util.UUID;

public final class Fixtures {
    public static final Instant NOW = Instant.parse("2026-09-28T12:00:00Z");
    public static ProcessingRequest request() {
        UUID owner = UUID.randomUUID(), video = UUID.randomUUID();
        return new ProcessingRequest(UUID.randomUUID(), video, owner, UUID.randomUUID(), NOW,
                "fiapx-media-test", "originals/" + owner + "/" + video + "/" + UUID.randomUUID(), 100, "a".repeat(64), "sample.mp4");
    }
    public static ProcessingLimits limits() {
        return new ProcessingLimits(100_000_000,300,1073741824,1073741824,600,3,3221225472L,120,30);
    }
    public static ResultArtifact artifact(JobSnapshot job) {
        return new ResultArtifact(job.request().bucket(), "results/" + job.request().ownerId() + "/"
                + job.request().videoId() + "/" + job.token() + "/frames.zip", 100, "b".repeat(64), 3);
    }
}
