package br.com.fiap.fiapx.processing.core.domain;

import java.time.Instant;
import java.util.UUID;

public record JobSnapshot(ProcessingRequest request, JobStatus status, UUID token, int attempt,
                          int mediaAttempts, long version, Instant leaseUntil, Instant retryAt,
                          boolean mediaStarted, ResultArtifact result, Instant startedAt,
                          Instant completedAt, Instant expiresAt, FailureCode failureCode) {}
