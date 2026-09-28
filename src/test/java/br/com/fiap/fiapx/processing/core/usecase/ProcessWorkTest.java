package br.com.fiap.fiapx.processing.core.usecase;

import br.com.fiap.fiapx.processing.core.domain.*;
import br.com.fiap.fiapx.processing.core.gateway.*;
import org.junit.jupiter.api.*;
import java.io.IOException;
import java.nio.file.Path;
import java.util.UUID;
import static br.com.fiap.fiapx.processing.Fixtures.*;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;
import static org.mockito.ArgumentMatchers.*;

class ProcessWorkTest {
    final JobGateway jobs=mock(JobGateway.class);
    final MediaGateway media=mock(MediaGateway.class);
    final StoreProcessingResult storage=mock(StoreProcessingResult.class);
    final ExecutionLease lease=mock(ExecutionLease.class);
    final ExecutionLease.Factory leases=mock(ExecutionLease.Factory.class);
    final ProcessingRequest request=request();
    final ProcessingJob domain=ProcessingJob.pending(request);
    final ProcessWork work=new ProcessWork(jobs,media,storage,limits());
    JobSnapshot running;
    @BeforeEach void setup() throws Exception {
        domain.acquire(UUID.randomUUID(),NOW,120); running=domain.snapshot();
        when(jobs.acquire(request)).thenReturn(new JobGateway.Claim(JobGateway.Disposition.ACQUIRED,running));
        when(leases.open(any())).thenReturn(lease); when(lease.getAsBoolean()).thenReturn(true);
        when(storage.download(any(),any())).thenReturn(Path.of("verified-original"));
        when(jobs.beginMedia(any(),any())).thenAnswer(call->{domain.beginMedia(running.token(),NOW,3); return domain.snapshot();});
        when(jobs.fail(any(),any(),any())).thenAnswer(call->{domain.fail(running.token(),NOW,call.getArgument(2)); return domain.snapshot();});
    }
    @Test void successRequiresStorageTerminalAfterDurableBeginAndClosesMedia() throws Exception {
        var output=mock(MediaGateway.LocalMediaResult.class);
        when(media.extract(any(),any())).thenReturn(output);
        when(storage.publish(any(),eq(output),any())).thenAnswer(call->{domain.prepareResult(running.token(),NOW,artifact(running),1000);
            domain.complete(running.token(),NOW); return domain.snapshot();});
        assertTrue(work.execute(request,leases));
        var order=inOrder(storage,jobs,media,output,lease);
        order.verify(storage).download(running,lease); order.verify(jobs).beginMedia(request.videoId(),running.token());
        order.verify(media).extract(Path.of("verified-original"),lease); order.verify(storage).publish(any(),eq(output),eq(lease));
        order.verify(output).close(); order.verify(lease).close();
    }
    @Test void terminalReplayAndActiveDuplicateNeverExecuteMedia() {
        when(jobs.acquire(request)).thenReturn(new JobGateway.Claim(JobGateway.Disposition.TERMINAL,running));
        assertTrue(work.execute(request,leases));
        when(jobs.acquire(request)).thenReturn(new JobGateway.Claim(JobGateway.Disposition.BUSY,running));
        assertFalse(work.execute(request,leases)); verifyNoInteractions(leases,storage,media);
    }
    @Test void uncertainAcquisitionCannotAuthorizeAck() {
        when(jobs.acquire(request)).thenThrow(new IllegalStateException("uncertain"));
        assertThrows(IllegalStateException.class,()->work.execute(request,leases)); verifyNoInteractions(media,leases);
    }
    @Test void deterministicMediaFailureIsDurablyTerminal() throws Exception {
        for (var code:new FailureCode[]{FailureCode.INVALID_MEDIA,FailureCode.DURATION_EXCEEDED,FailureCode.OUTPUT_LIMIT_EXCEEDED}) {
            reset(jobs); when(jobs.acquire(request)).thenReturn(new JobGateway.Claim(JobGateway.Disposition.ACQUIRED,running));
            when(jobs.beginMedia(any(),any())).thenReturn(running);
            var failed=new ProcessingJob(running); failed.fail(running.token(),NOW,code);
            when(jobs.fail(any(),any(),eq(code))).thenReturn(failed.snapshot());
            doThrow(new MediaFailure(code)).when(media).extract(any(),any());
            assertTrue(work.execute(request,leases)); verify(jobs).fail(request.videoId(),running.token(),code);
            verify(jobs,never()).defer(any(),any(),anyLong());
        }
    }
    @Test void timeoutRetriesThirtyThenSixtyAndThirdIsTerminal() throws Exception {
        when(media.extract(any(),any())).thenThrow(new MediaFailure(FailureCode.PROCESSING_TIMEOUT));
        assertFalse(work.execute(request,leases)); verify(jobs).defer(request.videoId(),running.token(),30);
        domain.defer(running.token(),NOW,30); domain.acquire(UUID.randomUUID(),NOW.plusSeconds(31),120);
        running=domain.snapshot(); when(jobs.acquire(request)).thenReturn(new JobGateway.Claim(JobGateway.Disposition.ACQUIRED,running));
        assertFalse(work.execute(request,leases)); verify(jobs).defer(request.videoId(),running.token(),60);
        domain.defer(running.token(),NOW.plusSeconds(31),60); domain.acquire(UUID.randomUUID(),NOW.plusSeconds(92),120);
        running=domain.snapshot(); when(jobs.acquire(request)).thenReturn(new JobGateway.Claim(JobGateway.Disposition.ACQUIRED,running));
        assertTrue(work.execute(request,leases)); verify(jobs).fail(request.videoId(),running.token(),FailureCode.PROCESSING_TIMEOUT);
    }
    @Test void exhaustedCrashBudgetFailsWithoutAnotherDownload() {
        for (int i=0;i<3;i++) { domain.beginMedia(domain.snapshot().token(),NOW.plusSeconds(i*121),3);
            domain.acquire(UUID.randomUUID(),NOW.plusSeconds((i+1)*121),120); }
        running=domain.snapshot(); when(jobs.acquire(request)).thenReturn(new JobGateway.Claim(JobGateway.Disposition.ACQUIRED,running));
        assertTrue(work.execute(request,leases)); verifyNoInteractions(media,storage);
    }
    @Test void pendingStorageRecoversBeforeMediaAndCanRequireLaterFreshClaim() throws Exception {
        domain.beginMedia(running.token(),NOW,3); domain.prepareResult(running.token(),NOW,artifact(running),1000);
        var pending=domain.snapshot(); when(jobs.acquire(request)).thenReturn(new JobGateway.Claim(JobGateway.Disposition.ACQUIRED,pending));
        domain.complete(running.token(),NOW); when(storage.recover(pending,lease)).thenReturn(domain.snapshot());
        assertTrue(work.execute(request,leases)); verifyNoInteractions(media);
        when(storage.recover(pending,lease)).thenReturn(running);
        assertFalse(work.execute(request,leases)); verify(storage,never()).download(any(),any());
    }
    @Test void dependencyFailureDoesNotSpendMediaAttemptOrAck() throws Exception {
        when(storage.download(any(),any())).thenThrow(new IOException("S3 unavailable"));
        assertFalse(work.execute(request,leases)); verify(jobs,never()).beginMedia(any(),any());
        verify(jobs).defer(request.videoId(),running.token(),30); verifyNoInteractions(media);
    }
    @Test void uncertainTerminalCommitAndFailedDeferralNeverAuthorizeAck() throws Exception {
        when(media.extract(any(),any())).thenThrow(new MediaFailure(FailureCode.INVALID_MEDIA));
        doThrow(new IllegalStateException("uncertain")).when(jobs).fail(any(),any(),any());
        when(jobs.defer(any(),any(),anyLong())).thenThrow(new IllegalStateException("db unavailable"));
        assertFalse(work.execute(request,leases)); verify(lease).close();
    }
    @Test void lostOwnershipBeforeDownloadAfterDownloadOrDuringMediaNeverFailsBusinessJob() throws Exception {
        when(lease.getAsBoolean()).thenReturn(false);
        assertFalse(work.execute(request,leases)); verifyNoInteractions(media,storage);
        when(lease.getAsBoolean()).thenReturn(true,false);
        assertFalse(work.execute(request,leases)); verify(jobs,never()).beginMedia(any(),any());
        when(lease.getAsBoolean()).thenReturn(true,true,false);
        when(media.extract(any(),any())).thenThrow(new MediaFailure(FailureCode.INVALID_MEDIA));
        assertFalse(work.execute(request,leases)); verify(jobs,never()).fail(any(),any(),any());
    }
    @Test void operationalMediaFailureAndLostLeaseOnStorageDoNotAck() throws Exception {
        when(media.extract(any(),any())).thenThrow(new IOException("disk"));
        assertFalse(work.execute(request,leases));
        verify(jobs).defer(request.videoId(),running.token(),30);
        reset(storage); when(storage.download(any(),any())).thenThrow(new IOException("lost"));
        when(lease.getAsBoolean()).thenReturn(true,false);
        assertFalse(work.execute(request,leases)); verify(jobs,times(1)).defer(any(),any(),anyLong());
    }
}
