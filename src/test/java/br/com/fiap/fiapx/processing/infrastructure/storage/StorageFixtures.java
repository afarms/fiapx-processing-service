package br.com.fiap.fiapx.processing.infrastructure.storage;

import br.com.fiap.fiapx.processing.core.domain.*;
import br.com.fiap.fiapx.processing.Fixtures;
import java.security.*;
import java.util.*;

public final class StorageFixtures {
    public static final byte[] BYTES = "verified artifact bytes".getBytes(java.nio.charset.StandardCharsets.UTF_8);
    public static String hash(byte[] bytes) {
        try { return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes)); }
        catch (NoSuchAlgorithmException e) { throw new AssertionError(e); }
    }
    public static ProcessingJob job() {
        var job = ProcessingJob.pending(request()); job.acquire(UUID.randomUUID(), Fixtures.NOW, 120);
        job.beginMedia(job.snapshot().token(), Fixtures.NOW, 3); return job;
    }
    public static ProcessingRequest request() {
        var r = Fixtures.request();
        return new ProcessingRequest(r.eventId(), r.videoId(), r.ownerId(), r.correlationId(), r.occurredAt(),
                r.bucket(), r.objectKey(), BYTES.length, hash(BYTES), r.originalName());
    }
    public static ResultArtifact artifact(JobSnapshot job) {
        return new ResultArtifact(job.request().bucket(), new ResultKey(job.request().ownerId(), job.request().videoId(), job.token()).objectKey(),
                BYTES.length, hash(BYTES), 1);
    }
}
