package br.com.fiap.fiapx.processing.core.usecase;

import br.com.fiap.fiapx.processing.core.domain.*;
import br.com.fiap.fiapx.processing.core.gateway.*;
import java.io.IOException;
import java.nio.file.Path;
import java.util.*;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;
import static org.mockito.ArgumentMatchers.*;
import static br.com.fiap.fiapx.processing.infrastructure.storage.StorageFixtures.*;
import static br.com.fiap.fiapx.processing.Fixtures.NOW;

class StoreProcessingResultTest {
    final JobGateway jobs = mock(JobGateway.class);
    final ObjectStorageGateway objects = mock(ObjectStorageGateway.class);
    final LocalArtifactsGateway files = mock(LocalArtifactsGateway.class);
    final StoreProcessingResult service = new StoreProcessingResult(jobs, objects, files);
    final ProcessingJob domain = job();
    final JobSnapshot running = domain.snapshot();
    final ResultArtifact artifact = artifact(running);
    JobSnapshot pending() { domain.prepareResult(running.token(),NOW,artifact,100000); return domain.snapshot(); }

    @Test void retainsBeforeIntentAndCommitsOnlyAfterObjectConfirmation() throws Exception {
        var pending = pending(); var media = mock(MediaGateway.LocalMediaResult.class);
        when(media.zip()).thenReturn(Path.of("media.zip")); when(media.sizeBytes()).thenReturn(artifact.sizeBytes());
        when(media.sha256()).thenReturn(artifact.sha256()); when(media.frameCount()).thenReturn(1);
        when(jobs.prepareResult(any(),any(),eq(artifact))).thenReturn(pending);
        when(files.find(eq(artifact),any())).thenReturn(Optional.of(Path.of("retained.zip")));
        domain.complete(running.token(),NOW); var terminal = domain.snapshot(); when(jobs.complete(any(),any())).thenReturn(terminal);
        assertEquals(terminal,service.publish(running,media,()->true));
        var order = inOrder(files,jobs,objects);
        order.verify(files).retain(eq(artifact),eq(Path.of("media.zip")),any());
        order.verify(jobs).prepareResult(running.request().videoId(),running.token(),artifact);
        order.verify(objects).present(eq(artifact),any()); order.verify(files).find(eq(artifact),any());
        order.verify(objects).store(eq(artifact),eq(Path.of("retained.zip")),any());
        order.verify(jobs).complete(running.request().videoId(),running.token()); order.verify(files).remove(artifact);
        verify(objects,never()).deleteAbandoned(any()); verify(media,never()).close();
    }
    @Test void confirmsAlreadyStoredResultWithoutReexecutingOrNeedingLocalCopy() throws Exception {
        var pending = pending(); domain.acquire(UUID.randomUUID(),NOW.plusSeconds(121),120); var takeover=domain.snapshot();
        when(objects.present(eq(artifact),any())).thenReturn(true); when(jobs.complete(any(),any())).thenReturn(takeover);
        service.recover(takeover,()->true);
        verify(jobs).complete(running.request().videoId(),takeover.token());
        verify(files,never()).find(any(),any()); verify(objects,never()).store(any(),any(),any());
        assertEquals(artifact,pending.result()); assertEquals(1,takeover.mediaAttempts());
    }
    @Test void uncertaintyNeverDiscardsIntentDeletesObjectOrRemovesRetainedFile() throws Exception {
        var pending = pending(); when(objects.present(eq(artifact),any())).thenThrow(new IOException("unavailable"));
        assertThrows(IOException.class,()->service.recover(pending,()->true));
        verifyNoInteractions(files); verifyNoInteractions(jobs);
        doReturn(true).when(objects).present(eq(artifact),any());
        when(jobs.complete(any(),any())).thenThrow(new IllegalStateException("uncertain commit"));
        assertThrows(IllegalStateException.class,()->service.recover(pending,()->true));
        verify(files,never()).remove(any()); verify(objects,never()).deleteAbandoned(any()); verify(jobs,never()).discardMissingResult(any(),any());
    }
    @Test void confirmedMissingRemoteAndLocalResultRequiresFreshLease() throws Exception {
        var pending=pending(); when(files.find(eq(artifact),any())).thenReturn(Optional.empty());
        service.recover(pending,()->true); verify(jobs).discardMissingResult(running.request().videoId(),running.token());
        verify(jobs,never()).complete(any(),any()); verify(objects,never()).store(any(),any(),any());
    }
    @Test void validatesReferenceAndOwnershipAndDownloadsBeforeMediaStarts() throws Exception {
        assertThrows(IllegalStateException.class,()->service.recover(running,()->false));
        assertThrows(IllegalArgumentException.class,()->service.recover(running,()->true));
        Path input=Path.of("input"); when(files.original(running)).thenReturn(input);
        assertEquals(input,service.download(running,()->true)); verify(objects).download(eq(running.request()),eq(input),any());
        verify(jobs,never()).beginMedia(any(),any());
        var pending=pending();
        for (int field=0;field<3;field++) {
            var key=ResultKey.parse(artifact.objectKey());
            var foreign=new ResultArtifact(field==2?"other-bucket":artifact.bucket(),new ResultKey(field==1?UUID.randomUUID():key.owner(),field==0?UUID.randomUUID():key.job(),key.producer()).objectKey(),artifact.sizeBytes(),artifact.sha256(),1);
            var state=new JobSnapshot(pending.request(),pending.status(),pending.token(),pending.attempt(),pending.mediaAttempts(),pending.version(),pending.leaseUntil(),pending.retryAt(),pending.mediaStarted(),foreign,pending.startedAt(),null,null,null);
            assertThrows(IllegalArgumentException.class,()->service.recover(state,()->true));
        }
    }
    @Test void cleanupRechecksEligibilityAndRotatesEvenAfterFailedDeletion() throws Exception {
        var eligibility=mock(ArtifactCleanupGateway.class); var cleanup=new CleanupProcessingArtifacts(eligibility,objects,files);
        var key=ResultKey.parse(artifact.objectKey()); when(eligibility.abandoned(10)).thenReturn(List.of(artifact));
        cleanup.run(10); verify(objects,never()).deleteAbandoned(any()); verify(files).cleanup(any());
        when(eligibility.remoteAllowed(key.job(),key.producer())).thenReturn(true);
        doThrow(new IOException("unavailable")).when(objects).deleteAbandoned(artifact);
        assertThrows(IOException.class,()->cleanup.run(10)); verify(eligibility,times(2)).checked(key.producer());
    }
}
