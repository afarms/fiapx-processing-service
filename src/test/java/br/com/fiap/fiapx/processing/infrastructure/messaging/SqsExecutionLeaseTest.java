package br.com.fiap.fiapx.processing.infrastructure.messaging;

import br.com.fiap.fiapx.processing.core.domain.*;
import br.com.fiap.fiapx.processing.core.gateway.*;
import java.util.UUID;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicLong;
import org.junit.jupiter.api.Test;
import software.amazon.awssdk.services.sqs.SqsClient;
import software.amazon.awssdk.services.sqs.model.*;
import static br.com.fiap.fiapx.processing.Fixtures.*;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;
import static org.mockito.ArgumentMatchers.*;

class SqsExecutionLeaseTest {
    final JobGateway jobs=mock(JobGateway.class);
    final SqsClient sqs=mock(SqsClient.class);
    final ScheduledExecutorService scheduler=mock(ScheduledExecutorService.class);
    final ScheduledFuture<?> future=mock(ScheduledFuture.class);
    final AtomicLong clock=new AtomicLong();
    final ProcessingJob domain=ProcessingJob.pending(request());
    SqsExecutionLease open() {
        domain.acquire(UUID.randomUUID(),NOW,120);
        doReturn(future).when(scheduler).scheduleWithFixedDelay(any(Runnable.class),eq(30L),eq(30L),eq(TimeUnit.SECONDS));
        return new SqsExecutionLease(jobs,domain.snapshot(),sqs,"queue","current-receipt",limits(),scheduler,clock::get);
    }
    @Test void renewsDatabaseBeforeVisibilityAndCancelsOnClose() {
        var lease=open(); assertTrue(lease.getAsBoolean());
        var order=inOrder(jobs,sqs); order.verify(jobs).heartbeat(domain.snapshot().request().videoId(),domain.snapshot().token());
        order.verify(sqs).changeMessageVisibility(argThat((ChangeMessageVisibilityRequest r)->r.visibilityTimeout()==120 && r.receiptHandle().equals("current-receipt")));
        clock.set(TimeUnit.SECONDS.toNanos(30)); lease.renew();
        clock.set(TimeUnit.SECONDS.toNanos(149)); assertTrue(lease.getAsBoolean());
        clock.set(TimeUnit.SECONDS.toNanos(150)); assertFalse(lease.getAsBoolean());
        lease.close(); lease.renew(); assertFalse(lease.getAsBoolean());
        verify(future).cancel(false); verify(jobs,times(2)).heartbeat(any(),any());
    }
    @Test void databaseOrSqsFailureImmediatelyRevokesOwnership() {
        when(jobs.heartbeat(any(),any())).thenThrow(new IllegalStateException("lease lost"));
        var db=open(); assertFalse(db.getAsBoolean()); verifyNoInteractions(sqs); db.close();
        reset(jobs); when(sqs.changeMessageVisibility(any(ChangeMessageVisibilityRequest.class))).thenThrow(new IllegalStateException("network"));
        var transport=open(); assertFalse(transport.getAsBoolean()); transport.close();
    }
    @Test void slowRenewalDoesNotExtendDeadlineFromResponseTime() {
        when(sqs.changeMessageVisibility(any(ChangeMessageVisibilityRequest.class))).thenAnswer(call->{clock.addAndGet(TimeUnit.SECONDS.toNanos(121)); return null;});
        var lease=open(); assertFalse(lease.getAsBoolean()); lease.close();
    }
}
