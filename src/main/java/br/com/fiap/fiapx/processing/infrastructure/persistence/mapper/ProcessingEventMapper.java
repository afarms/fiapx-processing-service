package br.com.fiap.fiapx.processing.infrastructure.persistence.mapper;

import br.com.fiap.fiapx.processing.core.domain.*;
import java.time.Instant;
import java.util.*;
import tools.jackson.databind.json.JsonMapper;

public final class ProcessingEventMapper {
    private final JsonMapper json;
    public ProcessingEventMapper(JsonMapper json) { this.json = Objects.requireNonNull(json); }

    public String envelope(UUID eventId, String type, JobSnapshot job, Instant now) {
        var payload = new LinkedHashMap<String, Object>();
        payload.put("jobId", job.request().videoId().toString());
        payload.put("attemptId", job.token().toString()); payload.put("attempt", job.attempt());
        payload.put("version", job.version());
        switch (type) {
            case "ProcessingStarted" -> payload.put("startedAt", now.toString());
            case "ProcessingCompleted" -> {
                payload.put("completedAt", job.completedAt().toString()); payload.put("expiresAt", job.expiresAt().toString());
                payload.put("bucket", job.result().bucket()); payload.put("objectKey", job.result().objectKey());
                payload.put("sizeBytes", job.result().sizeBytes()); payload.put("sha256", job.result().sha256());
                payload.put("frameCount", job.result().frameCount());
            }
            case "ProcessingFailed" -> {
                payload.put("failedAt", job.completedAt().toString()); payload.put("failureCode", job.failureCode().name());
            }
            default -> throw new IllegalArgumentException("Unsupported result event");
        }
        return json.writeValueAsString(Map.of("eventId", eventId.toString(), "eventType", type,
                "schemaVersion", 1, "aggregateId", job.request().videoId().toString(),
                "ownerId", job.request().ownerId().toString(), "correlationId", job.request().correlationId().toString(),
                "occurredAt", now.toString(), "payload", payload));
    }
}
