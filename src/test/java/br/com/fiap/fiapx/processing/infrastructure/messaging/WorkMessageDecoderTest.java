package br.com.fiap.fiapx.processing.infrastructure.messaging;

import br.com.fiap.fiapx.processing.core.domain.ProcessingRequest;
import java.util.*;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.json.JsonMapper;
import static br.com.fiap.fiapx.processing.Fixtures.*;
import static org.junit.jupiter.api.Assertions.*;

public class WorkMessageDecoderTest {
    static final JsonMapper JSON=JsonMapper.builder().build();
    public static String envelope(ProcessingRequest r) {
        return JSON.writeValueAsString(Map.of("eventId",r.eventId(),"aggregateId",r.videoId(),"ownerId",r.ownerId(),
                "correlationId",r.correlationId(),"occurredAt",r.occurredAt().toString(),"eventType","VideoProcessingRequested",
                "schemaVersion",1,"payload",Map.of("bucket",r.bucket(),"objectKey",r.objectKey(),"sizeBytes",r.sizeBytes(),
                        "sha256",r.sha256(),"originalName",r.originalName())));
    }
    @Test void acceptsActualProducerContractWithoutJwtOrIdentityService() {
        var r=request(); assertEquals(r,new WorkMessageDecoder(JSON,r.bucket()).decode(envelope(r)));
    }
    @Test void rejectsMalformedConflictingAndCoercedValues() {
        var r=request(); var decoder=new WorkMessageDecoder(JSON,r.bucket()); var body=envelope(r);
        for (String invalid:List.of("{}","[]","null","{", "x".repeat(16385),
                body.replace("VideoProcessingRequested","Other"),body.replace("\"schemaVersion\":1","\"schemaVersion\":2"),
                body.replace("\"schemaVersion\":1","\"schemaVersion\":\"1\""),
                body.replace("\"sizeBytes\":100","\"sizeBytes\":1.5"),
                body.replace("\"sizeBytes\":100","\"sizeBytes\":9223372036854775808"),
                body.replace(r.bucket(),"other-bucket"),body.replace(r.eventId().toString(),"1-1-1-1-1"),
                body.replace(r.sha256(),"bad"),body.replace("originals/","file:///"),
                body.replace("\"eventType\":\"VideoProcessingRequested\"","\"eventType\":42")))
            assertThrows(RuntimeException.class,()->decoder.decode(invalid),invalid);
        assertThrows(IllegalArgumentException.class,()->decoder.decode(null));
        assertThrows(IllegalArgumentException.class,()->new WorkMessageDecoder(JSON,""));
        assertThrows(IllegalArgumentException.class,()->new WorkMessageDecoder(JSON,null));
    }
}
