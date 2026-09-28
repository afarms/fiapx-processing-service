package br.com.fiap.fiapx.processing.infrastructure.persistence;

import br.com.fiap.fiapx.processing.core.domain.*;
import br.com.fiap.fiapx.processing.infrastructure.persistence.adapter.ArtifactCleanupAdapter;
import br.com.fiap.fiapx.processing.infrastructure.persistence.entity.ProcessingJobEntity;
import br.com.fiap.fiapx.processing.infrastructure.persistence.mapper.ProcessingJobMapper;
import br.com.fiap.fiapx.processing.infrastructure.persistence.repository.SpringProcessingJobRepository;
import java.util.*;
import org.junit.jupiter.api.Test;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.*;
import tools.jackson.databind.json.JsonMapper;
import static br.com.fiap.fiapx.processing.Fixtures.*;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;
import static org.mockito.ArgumentMatchers.*;

class ArtifactCleanupAdapterTest {
    final SpringProcessingJobRepository repo=mock(SpringProcessingJobRepository.class);
    final PlatformTransactionManager manager=mock(PlatformTransactionManager.class);
    final ProcessingJobMapper mapper=new ProcessingJobMapper(JsonMapper.builder().build());
    final ArtifactCleanupAdapter cleanup=new ArtifactCleanupAdapter(repo,mapper,new TransactionTemplate(manager));
    final ProcessingJobEntity row=new ProcessingJobEntity();
    final ProcessingJob job=ProcessingJob.pending(request());
    ArtifactCleanupAdapterTest() {
        job.acquire(UUID.randomUUID(),NOW,120); mapper.write(job.snapshot(),row);
        when(manager.getTransaction(any())).thenAnswer(inv->new SimpleTransactionStatus());
        when(repo.lock(row.id)).thenReturn(Optional.of(row)); when(repo.attemptBelongs(eq(row.id),any())).thenReturn(1);
        when(repo.databaseTime()).thenReturn(NOW);
    }
    @Test void locksBeforeDecisionAndProtectsActiveAndReferencedResults() {
        UUID producer=job.snapshot().token();
        assertFalse(cleanup.remoteAllowed(row.id,producer));
        var order=inOrder(repo,manager); order.verify(repo).lock(row.id); order.verify(repo).attemptBelongs(row.id,producer); order.verify(repo).databaseTime(); order.verify(manager).commit(any());
        job.beginMedia(producer,NOW,3); job.prepareResult(producer,NOW,artifact(job.snapshot()),1000); mapper.write(job.snapshot(),row);
        when(repo.databaseTime()).thenReturn(NOW.plusSeconds(121));
        assertFalse(cleanup.remoteAllowed(row.id,producer)); assertFalse(cleanup.localAllowed(row.id,producer));
        job.complete(producer,NOW); mapper.write(job.snapshot(),row);
        assertFalse(cleanup.remoteAllowed(row.id,producer)); assertTrue(cleanup.localAllowed(row.id,producer));
        assertTrue(cleanup.remoteAllowed(row.id,UUID.randomUUID()));
    }
    @Test void permitsOnlyKnownExpiredUnreferencedAttemptsAndRotatesScan() {
        UUID producer=job.snapshot().token(); when(repo.databaseTime()).thenReturn(NOW.plusSeconds(121));
        assertTrue(cleanup.remoteAllowed(row.id,producer)); assertTrue(cleanup.localAllowed(row.id,producer));
        assertFalse(cleanup.remoteAllowed(UUID.randomUUID(),producer));
        when(repo.attemptBelongs(row.id,producer)).thenReturn(0); assertFalse(cleanup.remoteAllowed(row.id,producer));
        var artifact=artifact(job.snapshot()); when(repo.abandonedResults(10)).thenReturn(List.of(mapper.result(artifact)));
        assertEquals(List.of(artifact),cleanup.abandoned(10)); cleanup.checked(producer); verify(repo).checkedResult(producer);
        assertThrows(IllegalArgumentException.class,()->cleanup.abandoned(0)); assertThrows(IllegalArgumentException.class,()->cleanup.abandoned(1001));
    }
}
