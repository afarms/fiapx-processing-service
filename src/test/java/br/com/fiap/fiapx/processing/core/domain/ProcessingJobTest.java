package br.com.fiap.fiapx.processing.core.domain;

import static br.com.fiap.fiapx.processing.Fixtures.*;
import static org.junit.jupiter.api.Assertions.*;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class ProcessingJobTest {
    @Test void onlyOneLiveOwnerAndOldTokenCannotRenewOrFinish() {
        var job = ProcessingJob.pending(request()); var first = UUID.randomUUID(); var next = UUID.randomUUID();
        assertThrows(IllegalStateException.class, () -> job.beginMedia(first,NOW,3));
        assertThrows(IllegalArgumentException.class, () -> job.acquire(first,NOW,0));
        assertTrue(job.acquire(first,NOW,120));
        assertFalse(job.acquire(next,NOW.plusSeconds(119),120));
        assertThrows(IllegalStateException.class, () -> job.heartbeat(next,NOW,120));
        assertThrows(IllegalArgumentException.class, () -> job.heartbeat(first,NOW,0));
        job.heartbeat(first,NOW.plusSeconds(30),120);
        assertThrows(IllegalStateException.class, () -> job.heartbeat(first,NOW.plusSeconds(150),120));
        assertTrue(job.acquire(next,NOW.plusSeconds(150),120));
        assertThrows(IllegalStateException.class, () -> job.fail(first,NOW.plusSeconds(151),FailureCode.INVALID_MEDIA));
        assertEquals(2, job.snapshot().attempt()); assertEquals(0, job.snapshot().mediaAttempts());
    }

    @Test void terminalResultIsImmutableAndExpiryStartsAtConclusion() {
        var job = ProcessingJob.pending(request()); var token = UUID.randomUUID(); job.acquire(token,NOW,120);
        assertThrows(IllegalStateException.class, () -> job.complete(token,NOW));
        assertThrows(IllegalStateException.class, () -> job.prepareResult(token,NOW,artifact(job.snapshot()),100));
        job.beginMedia(token,NOW,3);
        assertThrows(IllegalStateException.class, () -> job.beginMedia(token,NOW,3));
        assertThrows(IllegalArgumentException.class, () -> job.prepareResult(token,NOW,artifact(job.snapshot()),99));
        var wrong = new ResultArtifact("other-bucket","wrong",100,"b".repeat(64),1);
        assertThrows(IllegalArgumentException.class, () -> job.prepareResult(token,NOW,wrong,100));
        var wrongKey = new ResultArtifact(job.snapshot().request().bucket(),"wrong",100,"b".repeat(64),1);
        assertThrows(IllegalArgumentException.class, () -> job.prepareResult(token,NOW,wrongKey,100));
        job.prepareResult(token,NOW,artifact(job.snapshot()),100);
        assertThrows(IllegalStateException.class, () -> job.fail(token,NOW,FailureCode.PROCESSING_FAILED));
        assertThrows(IllegalStateException.class, () -> job.beginMedia(token,NOW,3));
        job.complete(token,NOW.plusSeconds(10));
        assertEquals(NOW.plusSeconds(86410),job.snapshot().expiresAt());
        assertEquals(2,job.snapshot().version());
        assertFalse(job.acquire(UUID.randomUUID(),NOW.plusSeconds(1000),120));
        assertThrows(IllegalStateException.class, () -> job.complete(token,NOW.plusSeconds(11)));
    }

    @Test void dependencyRecoveryPreservesResultWithoutSpendingMediaBudget() {
        var job = ProcessingJob.pending(request()); var token = UUID.randomUUID(); job.acquire(token,NOW,120);
        assertThrows(IllegalStateException.class, () -> job.discardMissingResult(token,NOW));
        assertThrows(IllegalArgumentException.class, () -> job.defer(token,NOW,0));
        job.beginMedia(token,NOW,3); job.prepareResult(token,NOW,artifact(job.snapshot()),100);
        var intended = job.snapshot().result(); job.defer(token,NOW,30);
        assertFalse(job.acquire(UUID.randomUUID(),NOW.plusSeconds(29),120));
        var next = UUID.randomUUID(); assertTrue(job.acquire(next,NOW.plusSeconds(30),120));
        assertEquals(JobStatus.RESULT_PENDING_STORAGE,job.snapshot().status());
        assertEquals(intended,job.snapshot().result()); assertEquals(1,job.snapshot().mediaAttempts());
        job.complete(next,NOW.plusSeconds(31));
        assertEquals(intended,job.snapshot().result());
    }

    @Test void onlyThreeMediaExecutionsEvenAcrossCrashesAndOwnershipChanges() {
        var job = ProcessingJob.pending(request()); var token = UUID.randomUUID();
        for (int i=0;i<3;i++) {
            token = UUID.randomUUID(); assertTrue(job.acquire(token,NOW.plusSeconds(120L*i),120));
            job.beginMedia(token,NOW.plusSeconds(120L*i),3);
        }
        UUID exhausted = UUID.randomUUID(); job.acquire(exhausted,NOW.plusSeconds(360),120);
        assertThrows(IllegalStateException.class, () -> job.beginMedia(exhausted,NOW.plusSeconds(360),3));
        job.fail(exhausted,NOW.plusSeconds(360),FailureCode.PROCESSING_TIMEOUT);
        assertEquals(JobStatus.FAILED,job.snapshot().status()); assertNull(job.snapshot().expiresAt());
        assertEquals(FailureCode.PROCESSING_TIMEOUT,job.snapshot().failureCode());
    }

    @Test void missingResultRequiresExplicitDiscardBeforeRerunningMedia() {
        var job = ProcessingJob.pending(request()); var token = UUID.randomUUID(); job.acquire(token,NOW,120);
        job.beginMedia(token,NOW,3); job.prepareResult(token,NOW,artifact(job.snapshot()),100);
        job.discardMissingResult(token,NOW);
        var next = UUID.randomUUID(); job.acquire(next,NOW,120); job.beginMedia(next,NOW,3);
        assertEquals(2,job.snapshot().mediaAttempts()); assertNull(job.snapshot().result());
        job.fail(next,NOW,FailureCode.INVALID_MEDIA);
    }
}
