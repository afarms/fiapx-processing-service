package br.com.fiap.fiapx.processing.core.usecase;

import br.com.fiap.fiapx.processing.core.domain.*;
import br.com.fiap.fiapx.processing.core.gateway.*;
import java.io.IOException;
import java.nio.file.Path;
import java.util.function.BooleanSupplier;

/** Storage phase only: the consumer owns admission, beginMedia, heartbeat, retry and ACK. */
public final class StoreProcessingResult {
    private final JobGateway jobs;
    private final ObjectStorageGateway objects;
    private final LocalArtifactsGateway files;
    public StoreProcessingResult(JobGateway jobs, ObjectStorageGateway objects, LocalArtifactsGateway files) {
        this.jobs = jobs; this.objects = objects; this.files = files;
    }
    public Path download(JobSnapshot job, BooleanSupplier owned) throws IOException {
        check(owned);
        Path input = files.original(job);
        objects.download(job.request(), input, owned);
        return input;
    }
    public JobSnapshot publish(JobSnapshot job, MediaGateway.LocalMediaResult media, BooleanSupplier owned) throws IOException {
        check(owned);
        var key = new ResultKey(job.request().ownerId(), job.request().videoId(), job.token());
        var artifact = new ResultArtifact(job.request().bucket(), key.objectKey(), media.sizeBytes(), media.sha256(), media.frameCount());
        // Retain before the SQL intent, so uncertain commits never destroy the only recovery copy.
        files.retain(artifact, media.zip(), owned);
        JobSnapshot pending = jobs.prepareResult(job.request().videoId(), job.token(), artifact);
        return recover(pending, owned);
    }
    public JobSnapshot recover(JobSnapshot job, BooleanSupplier owned) throws IOException {
        check(owned);
        var artifact = job.result();
        if (artifact == null || job.status() != JobStatus.RESULT_PENDING_STORAGE)
            throw new IllegalArgumentException("A pending result is required");
        var reference = ResultKey.parse(artifact.objectKey());
        if (!reference.job().equals(job.request().videoId()) || !reference.owner().equals(job.request().ownerId())
                || !artifact.bucket().equals(job.request().bucket())) throw new IllegalArgumentException("Conflicting result identity");
        if (!objects.present(artifact, owned)) {
            var local = files.find(artifact, owned);
            if (local.isEmpty()) {
                check(owned);
                // Expire this lease. A subsequent execution must acquire a fresh token/key.
                return jobs.discardMissingResult(job.request().videoId(), job.token());
            }
            objects.store(artifact, local.get(), owned);
        }
        check(owned);
        JobSnapshot terminal = jobs.complete(job.request().videoId(), job.token());
        // Never delete S3 on any error, including uncertain terminal commit.
        files.remove(artifact);
        return terminal;
    }
    private static void check(BooleanSupplier owned) {
        if (!owned.getAsBoolean()) throw new IllegalStateException("Execution lease lost");
    }
}
