package br.com.fiap.fiapx.processing.infrastructure.persistence;

import static br.com.fiap.fiapx.processing.Fixtures.*;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;
import br.com.fiap.fiapx.processing.core.domain.*;
import br.com.fiap.fiapx.processing.core.gateway.JobGateway;
import br.com.fiap.fiapx.processing.infrastructure.persistence.adapter.ProcessingJobGatewayAdapter;
import br.com.fiap.fiapx.processing.infrastructure.persistence.entity.ProcessingJobEntity;
import br.com.fiap.fiapx.processing.infrastructure.persistence.mapper.*;
import br.com.fiap.fiapx.processing.infrastructure.persistence.repository.SpringProcessingJobRepository;
import java.util.*;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.transaction.*;
import org.springframework.transaction.support.*;
import tools.jackson.databind.json.JsonMapper;

class ProcessingJobGatewayAdapterTest {
    final JsonMapper json = JsonMapper.builder().build();
    final ProcessingJobMapper mapper = new ProcessingJobMapper(json);
    final ProcessingEventMapper events = new ProcessingEventMapper(json);
    final SpringProcessingJobRepository repo = mock(SpringProcessingJobRepository.class);
    final PlatformTransactionManager manager = mock(PlatformTransactionManager.class);
    final ProcessingRequest request = request();
    final ProcessingJobEntity entity = new ProcessingJobEntity();
    ProcessingJobGatewayAdapter gateway;

    @BeforeEach void setup() {
        when(manager.getTransaction(any())).thenAnswer(inv -> new SimpleTransactionStatus());
        mapper.write(ProcessingJob.pending(request).snapshot(),entity);
        when(repo.lock(request.videoId())).thenReturn(Optional.of(entity));
        when(repo.databaseTime()).thenReturn(NOW);
        when(repo.inboxRequest(request.eventId())).thenReturn(mapper.request(request));
        when(repo.terminalEvents(any(),anyLong())).thenReturn(1);
        gateway = new ProcessingJobGatewayAdapter(repo,mapper,events,new TransactionTemplate(manager),limits());
    }

    @Test void commitHappensAfterTerminalOutboxAndInboxAreStored() {
        var claim = gateway.acquire(request); var id=request.videoId(); var token=claim.job().token();
        assertEquals(JobGateway.Disposition.ACQUIRED,claim.disposition());
        assertEquals(JobGateway.Disposition.BUSY,gateway.acquire(request).disposition());
        gateway.heartbeat(id,token); gateway.beginMedia(id,token);
        gateway.prepareResult(id,token,artifact(claim.job()));
        clearInvocations(manager,repo);
        var terminal = gateway.complete(id,token);
        assertEquals(JobStatus.COMPLETED,terminal.status());
        var ordered=inOrder(repo,manager);
        ordered.verify(repo).saveAndFlush(entity);
        ordered.verify(repo).enqueue(any(),eq(id),eq(2L),eq("ProcessingCompleted"),anyString(),eq(NOW));
        ordered.verify(repo).finishInbox(id,NOW); ordered.verify(repo).endAttempts(id,NOW);
        ordered.verify(manager).commit(any());
        assertEquals(JobGateway.Disposition.TERMINAL,gateway.acquire(request).disposition());
        verify(repo).rescheduleTerminal(id,terminal.version());
    }

    @Test void durableFailureAndVersionedEnvelopeRoundTrip() {
        var claim=gateway.acquire(request);
        var failed=gateway.fail(request.videoId(),claim.job().token(),FailureCode.INVALID_MEDIA);
        assertEquals(FailureCode.INVALID_MEDIA,failed.failureCode());
        assertEquals(failed,mapper.read(entity).snapshot());
        String body=events.envelope(UUID.randomUUID(),"ProcessingFailed",failed,NOW);
        assertEquals("INVALID_MEDIA",json.readTree(body).get("payload").get("failureCode").asText());
        assertThrows(IllegalArgumentException.class,()->events.envelope(UUID.randomUUID(),"unknown",failed,NOW));
        assertEquals(JobGateway.Disposition.TERMINAL,gateway.acquire(request).disposition());
        when(repo.terminalEvents(any(),anyLong())).thenReturn(0);
        assertThrows(IllegalStateException.class,()->gateway.acquire(request));
    }

    @Test void rollbackOrUncertainCommitNeverReturnsTerminalReceipt() {
        var claim=gateway.acquire(request);
        doThrow(new IllegalStateException("outbox write failed")).when(repo)
                .enqueue(any(),any(),anyLong(),eq("ProcessingFailed"),anyString(),any());
        assertThrows(IllegalStateException.class,()->gateway.fail(request.videoId(),claim.job().token(),FailureCode.INVALID_MEDIA));
        verify(manager).rollback(any());
        // A fresh fixture state represents the database rollback (mock does not implement SQL).
        mapper.write(claim.job(),entity); reset(repo); when(repo.lock(request.videoId())).thenReturn(Optional.of(entity));
        when(repo.databaseTime()).thenReturn(NOW);
        doThrow(new TransactionSystemException("commit outcome unknown")).when(manager).commit(any());
        assertThrows(TransactionSystemException.class,()->gateway.fail(request.videoId(),claim.job().token(),FailureCode.INVALID_MEDIA));
    }

    @Test void conflictsAndAbsentJobFailWithoutSuccessfulReceipt() {
        var other=new ProcessingRequest(request.eventId(),request.videoId(),request.ownerId(),request.correlationId(),NOW,
                request.bucket(),request.objectKey(),101,request.sha256(),request.originalName());
        assertThrows(IllegalArgumentException.class,()->gateway.acquire(other));
        when(repo.inboxRequest(request.eventId())).thenReturn("different payload");
        assertThrows(IllegalArgumentException.class,()->gateway.acquire(request));
        assertThrows(IllegalArgumentException.class,()->gateway.heartbeat(UUID.randomUUID(),UUID.randomUUID()));
    }

    @Test void retryAndMissingArtifactDoNotOverwriteOldIntent() {
        var first=gateway.acquire(request);
        gateway.defer(request.videoId(),first.job().token(),30);
        assertEquals(JobGateway.Disposition.BUSY,gateway.acquire(request).disposition());
        when(repo.databaseTime()).thenReturn(NOW.plusSeconds(30));
        var next=gateway.acquire(request); gateway.beginMedia(request.videoId(),next.job().token());
        gateway.prepareResult(request.videoId(),next.job().token(),artifact(next.job()));
        gateway.discardMissingResult(request.videoId(),next.job().token());
        assertThrows(IllegalStateException.class,()->gateway.beginMedia(request.videoId(),next.job().token()));
        assertEquals(JobGateway.Disposition.ACQUIRED,gateway.acquire(request).disposition());
    }
}
