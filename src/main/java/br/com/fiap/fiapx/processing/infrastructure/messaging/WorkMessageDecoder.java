package br.com.fiap.fiapx.processing.infrastructure.messaging;

import br.com.fiap.fiapx.processing.core.domain.ProcessingRequest;
import java.time.Instant;
import java.util.UUID;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

public final class WorkMessageDecoder {
    private final JsonMapper json;
    private final String bucket;
    public WorkMessageDecoder(JsonMapper json, String bucket) {
        if (bucket==null || bucket.isBlank()) throw new IllegalArgumentException("Configure media bucket");
        this.json=json; this.bucket=bucket;
    }
    public ProcessingRequest decode(String body) {
        if (body==null || body.length()>16384) throw new IllegalArgumentException("Invalid envelope size");
        var root=json.readTree(body);
        if (!"VideoProcessingRequested".equals(text(root,"eventType")) || number(root,"schemaVersion")!=1)
            throw new IllegalArgumentException("Unsupported work envelope");
        var p=root.path("payload");
        if (!bucket.equals(text(p,"bucket"))) throw new IllegalArgumentException("Unexpected bucket");
        return new ProcessingRequest(uuid(root,"eventId"),uuid(root,"aggregateId"),uuid(root,"ownerId"),
                uuid(root,"correlationId"),Instant.parse(text(root,"occurredAt")),bucket,text(p,"objectKey"),
                number(p,"sizeBytes"),text(p,"sha256"),text(p,"originalName"));
    }
    private static String text(JsonNode node,String field) {
        var value=node.path(field);
        if (!value.isString()) throw new IllegalArgumentException("Invalid envelope field");
        return value.asString();
    }
    private static long number(JsonNode node,String field) {
        var value=node.path(field);
        if (!value.isIntegralNumber() || !value.canConvertToLong()) throw new IllegalArgumentException("Invalid number");
        return value.longValue();
    }
    private static UUID uuid(JsonNode node,String field) {
        String value=text(node,field); UUID id=UUID.fromString(value);
        if (!id.toString().equals(value)) throw new IllegalArgumentException("Invalid UUID");
        return id;
    }
}
