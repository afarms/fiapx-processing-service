package br.com.fiap.fiapx.processing.core.domain;

import static br.com.fiap.fiapx.processing.Fixtures.*;
import static org.junit.jupiter.api.Assertions.*;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class ProcessingRequestTest {
    ProcessingRequest copy(ProcessingRequest r,String bucket,String key,long size,String hash,String name) {
        return new ProcessingRequest(r.eventId(),r.videoId(),r.ownerId(),r.correlationId(),r.occurredAt(),bucket,key,size,hash,name);
    }

    @Test void validatesReferencesInputAndFingerprint() {
        var r=request();
        assertEquals(r,copy(r,r.bucket(),r.objectKey(),r.sizeBytes(),r.sha256(),r.originalName()));
        for(String bucket:new String[]{null,"", "https://example.com"})
            assertThrows(IllegalArgumentException.class,()->copy(r,bucket,r.objectKey(),1,r.sha256(),"a.mp4"));
        for(String key:new String[]{null,"wrong","originals/"+r.ownerId()+"/"+r.videoId()+"/../secret"})
            assertThrows(IllegalArgumentException.class,()->copy(r,r.bucket(),key,1,r.sha256(),"a.mp4"));
        for(long size:new long[]{0,-1,100_000_001})
            assertThrows(IllegalArgumentException.class,()->copy(r,r.bucket(),r.objectKey(),size,r.sha256(),"a.mp4"));
        for(String hash:new String[]{null,"wrong"})
            assertThrows(IllegalArgumentException.class,()->copy(r,r.bucket(),r.objectKey(),1,hash,"a.mp4"));
        for(String name:new String[]{null,"", " ","a".repeat(256),"../file.mp4","a\\b.mp4","a\n.mp4"})
            assertThrows(IllegalArgumentException.class,()->copy(r,r.bucket(),r.objectKey(),1,r.sha256(),name));
        assertDoesNotThrow(()->copy(r,r.bucket(),r.objectKey(),100_000_000,r.sha256(),"a.mp4"));
    }

    @Test void logicalWorkIgnoresNewEnvelopeButRejectsChangedContentOrOwner() {
        var r=request();
        var redrive=new ProcessingRequest(UUID.randomUUID(),r.videoId(),r.ownerId(),UUID.randomUUID(),NOW.plusSeconds(1),
                r.bucket(),r.objectKey(),r.sizeBytes(),r.sha256(),r.originalName());
        assertTrue(r.sameWork(redrive)); assertFalse(r.sameWork(request()));
        assertFalse(r.sameWork(copy(r,"other-bucket",r.objectKey(),r.sizeBytes(),r.sha256(),r.originalName())));
        assertFalse(r.sameWork(copy(r,r.bucket(),"originals/"+r.ownerId()+"/"+r.videoId()+"/"+UUID.randomUUID(),r.sizeBytes(),r.sha256(),r.originalName())));
        assertFalse(r.sameWork(copy(r,r.bucket(),r.objectKey(),1,r.sha256(),r.originalName())));
        assertFalse(r.sameWork(copy(r,r.bucket(),r.objectKey(),r.sizeBytes(),"b".repeat(64),r.originalName())));
        assertFalse(r.sameWork(copy(r,r.bucket(),r.objectKey(),r.sizeBytes(),r.sha256(),"other.mp4")));
        UUID owner=UUID.randomUUID();
        assertFalse(r.sameWork(new ProcessingRequest(UUID.randomUUID(),r.videoId(),owner,r.correlationId(),NOW,
                r.bucket(),"originals/"+owner+"/"+r.videoId()+"/"+UUID.randomUUID(),r.sizeBytes(),r.sha256(),r.originalName())));
    }

    @Test void rejectsInvalidResultMetadata() {
        assertThrows(IllegalArgumentException.class,()->new ResultArtifact(null,"key",1,"a".repeat(64),1));
        assertThrows(IllegalArgumentException.class,()->new ResultArtifact(" ","key",1,"a".repeat(64),1));
        assertThrows(IllegalArgumentException.class,()->new ResultArtifact("bucket",null,1,"a".repeat(64),1));
        assertThrows(IllegalArgumentException.class,()->new ResultArtifact("bucket","",1,"a".repeat(64),1));
        assertThrows(IllegalArgumentException.class,()->new ResultArtifact("bucket","key",0,"a".repeat(64),1));
        assertThrows(IllegalArgumentException.class,()->new ResultArtifact("bucket","key",1,null,1));
        assertThrows(IllegalArgumentException.class,()->new ResultArtifact("bucket","key",1,"bad",1));
        assertThrows(IllegalArgumentException.class,()->new ResultArtifact("bucket","key",1,"a".repeat(64),0));
    }
}
