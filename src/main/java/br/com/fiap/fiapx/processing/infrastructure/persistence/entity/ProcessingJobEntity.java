package br.com.fiap.fiapx.processing.infrastructure.persistence.entity;

import jakarta.persistence.*;
import java.time.Instant;
import java.util.UUID;

@Entity
@Table(name = "processing_jobs")
public class ProcessingJobEntity {
    @Id public UUID id;
    @Column(name = "owner_id", nullable = false) public UUID ownerId;
    @Column(name = "request_json", nullable = false, columnDefinition = "text") public String requestJson;
    @Column(nullable = false, length = 32) public String status;
    public UUID token;
    public int attempt;
    @Column(name = "media_attempts") public int mediaAttempts;
    @Column(name = "event_version") public long eventVersion;
    @Column(name = "lease_until") public Instant leaseUntil;
    @Column(name = "retry_at") public Instant retryAt;
    @Column(name = "media_started") public boolean mediaStarted;
    @Column(name = "result_json", columnDefinition = "text") public String resultJson;
    @Column(name = "started_at") public Instant startedAt;
    @Column(name = "completed_at") public Instant completedAt;
    @Column(name = "expires_at") public Instant expiresAt;
    @Column(name = "failure_code", length = 64) public String failureCode;
}
