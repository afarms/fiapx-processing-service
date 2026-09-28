package br.com.fiap.fiapx.processing.core.domain;

import java.time.Instant;
import java.util.Objects;
import java.util.UUID;

/** Validated logical request, independent of a transport receipt handle. */
public record ProcessingRequest(UUID eventId, UUID videoId, UUID ownerId, UUID correlationId,
                                Instant occurredAt, String bucket, String objectKey,
                                long sizeBytes, String sha256, String originalName) {
    public ProcessingRequest {
        Objects.requireNonNull(eventId); Objects.requireNonNull(videoId); Objects.requireNonNull(ownerId);
        Objects.requireNonNull(correlationId); Objects.requireNonNull(occurredAt);
        require(bucket != null && bucket.matches("[a-z0-9][a-z0-9.-]{1,61}[a-z0-9]"), "Invalid bucket");
        String prefix = "originals/" + ownerId + "/" + videoId + "/";
        require(objectKey != null && objectKey.startsWith(prefix), "Invalid original reference");
        String attempt = objectKey.substring(prefix.length());
        require(attempt.matches("[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}"), "Invalid original attempt");
        require(sizeBytes > 0 && sizeBytes <= 100_000_000, "Invalid input size");
        require(sha256 != null && sha256.matches("[0-9a-f]{64}"), "Invalid SHA-256");
        require(originalName != null && !originalName.isBlank() && originalName.length() <= 255
                && !originalName.contains("/") && !originalName.contains("\\")
                && originalName.chars().noneMatch(Character::isISOControl), "Invalid original name");
    }

    public boolean sameWork(ProcessingRequest other) {
        return videoId.equals(other.videoId) && ownerId.equals(other.ownerId)
                && bucket.equals(other.bucket) && objectKey.equals(other.objectKey)
                && sizeBytes == other.sizeBytes && sha256.equals(other.sha256) && originalName.equals(other.originalName);
    }

    public static void require(boolean condition, String message) {
        if (!condition) throw new IllegalArgumentException(message);
    }
}
