package br.com.fiap.fiapx.processing.infrastructure.persistence.mapper;

import br.com.fiap.fiapx.processing.core.domain.*;
import br.com.fiap.fiapx.processing.infrastructure.persistence.entity.ProcessingJobEntity;
import tools.jackson.databind.json.JsonMapper;
import java.util.Objects;

public final class ProcessingJobMapper {
    private final JsonMapper json;
    public ProcessingJobMapper(JsonMapper json) { this.json = Objects.requireNonNull(json); }

    public String request(ProcessingRequest request) { return json.writeValueAsString(request); }
    public String result(ResultArtifact result) { return json.writeValueAsString(result); }

    public ProcessingJob read(ProcessingJobEntity e) {
        return new ProcessingJob(new JobSnapshot(json.readValue(e.requestJson, ProcessingRequest.class),
                JobStatus.valueOf(e.status), e.token, e.attempt, e.mediaAttempts, e.eventVersion,
                e.leaseUntil, e.retryAt, e.mediaStarted,
                e.resultJson == null ? null : json.readValue(e.resultJson, ResultArtifact.class),
                e.startedAt, e.completedAt, e.expiresAt,
                e.failureCode == null ? null : FailureCode.valueOf(e.failureCode)));
    }

    public void write(JobSnapshot s, ProcessingJobEntity e) {
        e.id = s.request().videoId(); e.ownerId = s.request().ownerId(); e.requestJson = request(s.request());
        e.status = s.status().name(); e.token = s.token(); e.attempt = s.attempt(); e.mediaAttempts = s.mediaAttempts();
        e.eventVersion = s.version(); e.leaseUntil = s.leaseUntil(); e.retryAt = s.retryAt();
        e.mediaStarted = s.mediaStarted(); e.resultJson = s.result() == null ? null : result(s.result());
        e.startedAt = s.startedAt(); e.completedAt = s.completedAt(); e.expiresAt = s.expiresAt();
        e.failureCode = s.failureCode() == null ? null : s.failureCode().name();
    }
}
