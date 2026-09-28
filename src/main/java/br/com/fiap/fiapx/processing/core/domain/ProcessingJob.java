package br.com.fiap.fiapx.processing.core.domain;

import java.time.Instant;
import java.util.Objects;
import java.util.UUID;
import static br.com.fiap.fiapx.processing.core.domain.ProcessingRequest.require;

/** Called under a short database row lock, using the database clock. */
public final class ProcessingJob {
    private final ProcessingRequest request;
    private JobStatus status;
    private UUID token;
    private int attempt, mediaAttempts;
    private long version;
    private Instant leaseUntil, retryAt, startedAt, completedAt, expiresAt;
    private boolean mediaStarted;
    private ResultArtifact result;
    private FailureCode failureCode;

    public ProcessingJob(JobSnapshot s) {
        request = Objects.requireNonNull(s.request()); status = Objects.requireNonNull(s.status());
        token = s.token(); attempt = s.attempt(); mediaAttempts = s.mediaAttempts(); version = s.version();
        leaseUntil = s.leaseUntil(); retryAt = s.retryAt(); mediaStarted = s.mediaStarted(); result = s.result();
        startedAt = s.startedAt(); completedAt = s.completedAt(); expiresAt = s.expiresAt(); failureCode = s.failureCode();
    }

    public static ProcessingJob pending(ProcessingRequest request) {
        return new ProcessingJob(new JobSnapshot(request, JobStatus.PENDING, null, 0, 0, 0,
                null, null, false, null, null, null, null, null));
    }

    public JobSnapshot snapshot() {
        return new JobSnapshot(request, status, token, attempt, mediaAttempts, version, leaseUntil,
                retryAt, mediaStarted, result, startedAt, completedAt, expiresAt, failureCode);
    }

    public boolean acquire(UUID nextToken, Instant now, long leaseSeconds) {
        Objects.requireNonNull(nextToken);
        require(leaseSeconds > 0, "Invalid lease");
        if (status.terminal()) return false;
        if (leaseUntil != null && leaseUntil.isAfter(now)) return false;
        if (retryAt != null && retryAt.isAfter(now)) return false;
        token = nextToken; attempt++; leaseUntil = now.plusSeconds(leaseSeconds); retryAt = null;
        mediaStarted = false;
        status = result == null ? JobStatus.RUNNING : JobStatus.RESULT_PENDING_STORAGE;
        if (startedAt == null) startedAt = now;
        version++;
        return true;
    }

    private void owned(UUID expected, Instant now) {
        if (status.terminal() || token == null || !token.equals(expected)
                || leaseUntil == null || !leaseUntil.isAfter(now)) throw new IllegalStateException("Execution lease lost");
    }

    public void heartbeat(UUID expected, Instant now, long seconds) {
        owned(expected, now); require(seconds > 0, "Invalid lease"); leaseUntil = now.plusSeconds(seconds);
    }

    public void beginMedia(UUID expected, Instant now, int maxAttempts) {
        owned(expected, now);
        if (status != JobStatus.RUNNING || mediaStarted || mediaAttempts >= maxAttempts)
            throw new IllegalStateException("Media execution cannot start");
        mediaAttempts++; mediaStarted = true;
    }

    public void prepareResult(UUID expected, Instant now, ResultArtifact artifact, long maxZipBytes) {
        owned(expected, now);
        if (status != JobStatus.RUNNING || !mediaStarted) throw new IllegalStateException("Media has not started");
        String key = "results/" + request.ownerId() + "/" + request.videoId() + "/" + token + "/frames.zip";
        require(request.bucket().equals(artifact.bucket()) && key.equals(artifact.objectKey()), "Invalid result reference");
        require(artifact.sizeBytes() <= maxZipBytes, "Result exceeds limit");
        result = artifact; status = JobStatus.RESULT_PENDING_STORAGE;
    }

    /** Caller has verified storage and the local artifact are unavailable. */
    public void discardMissingResult(UUID expected, Instant now) {
        owned(expected, now);
        if (status != JobStatus.RESULT_PENDING_STORAGE) throw new IllegalStateException("No pending result");
        result = null; mediaStarted = false; status = JobStatus.RETRY_WAIT; retryAt = now; leaseUntil = now;
    }

    /** Persist retry without spending a media attempt for a dependency outage. */
    public void defer(UUID expected, Instant now, long delaySeconds) {
        owned(expected, now); require(delaySeconds > 0, "Invalid retry delay");
        status = JobStatus.RETRY_WAIT; retryAt = now.plusSeconds(delaySeconds); leaseUntil = now;
    }

    /** Caller must confirm the intended immutable S3 object before invoking this. */
    public void complete(UUID expected, Instant now) {
        owned(expected, now);
        if (status != JobStatus.RESULT_PENDING_STORAGE) throw new IllegalStateException("No result to confirm");
        status = JobStatus.COMPLETED; completedAt = now; expiresAt = now.plusSeconds(86400); version++; leaseUntil = now;
    }

    public void fail(UUID expected, Instant now, FailureCode code) {
        owned(expected, now);
        if (result != null) throw new IllegalStateException("Resolve pending result before failure");
        failureCode = Objects.requireNonNull(code); status = JobStatus.FAILED; completedAt = now; version++; leaseUntil = now;
    }
}
