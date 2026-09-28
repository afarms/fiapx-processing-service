package br.com.fiap.fiapx.processing.infrastructure.messaging;

import br.com.fiap.fiapx.processing.core.domain.*;
import br.com.fiap.fiapx.processing.core.gateway.*;
import br.com.fiap.fiapx.processing.core.usecase.*;
import java.util.*;
import java.util.concurrent.*;
import org.junit.jupiter.api.*;
import software.amazon.awssdk.services.sqs.SqsClient;
import software.amazon.awssdk.services.sqs.model.*;
import static br.com.fiap.fiapx.processing.Fixtures.*;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;
import static org.mockito.ArgumentMatchers.*;

class WorkConsumerTest {
    static final String QUEUE="https://sqs.us-east-1.amazonaws.com/123456789012/work";
    final SqsClient sqs=mock(SqsClient.class);
    final JobGateway jobs=mock(JobGateway.class);
    final ProcessWork work=mock(ProcessWork.class);
    final ScheduledExecutorService scheduler=mock(ScheduledExecutorService.class);
    final ScheduledFuture<?> future=mock(ScheduledFuture.class);
    final ProcessingRequest request=request();
    final WorkConsumer consumer=new WorkConsumer(sqs,QUEUE,new WorkMessageDecoder(WorkMessageDecoderTest.JSON,request.bucket()),work,jobs,limits(),scheduler,()->true);
    @BeforeEach void setup() {
        delivery(WorkMessageDecoderTest.envelope(request));
        doReturn(future).when(scheduler).scheduleWithFixedDelay(any(Runnable.class),anyLong(),anyLong(),any());
    }
    void delivery(String body) { when(sqs.receiveMessage(any(ReceiveMessageRequest.class))).thenReturn(ReceiveMessageResponse.builder()
            .messages(Message.builder().body(body).messageId("transport-id").receiptHandle("fresh-receipt").build()).build()); }
    @Test void deletesOnlyExplicitDurableResultAndUsesCurrentReceipt() {
        when(work.execute(eq(request),any())).thenReturn(true); consumer.poll();
        verify(sqs).receiveMessage(argThat((ReceiveMessageRequest r)->r.maxNumberOfMessages()==1 && r.waitTimeSeconds()==20));
        var order=inOrder(work,sqs); order.verify(work).execute(eq(request),any());
        order.verify(sqs).deleteMessage(argThat((DeleteMessageRequest r)->r.receiptHandle().equals("fresh-receipt") && r.queueUrl().equals(QUEUE)));
    }
    @Test void nonterminalAndCommitExceptionNeverDelete() {
        consumer.poll(); verify(sqs).changeMessageVisibility(argThat((ChangeMessageVisibilityRequest r)->r.visibilityTimeout()==30));
        when(work.execute(any(),any())).thenThrow(new IllegalStateException("uncertain commit")); consumer.poll();
        verify(sqs,never()).deleteMessage(any(DeleteMessageRequest.class));
    }
    @Test void malformedAndReceiveFailureDoNotCreateBusinessFailureOrAck() {
        delivery("{}"); consumer.poll(); verifyNoInteractions(work,jobs);
        when(sqs.receiveMessage(any(ReceiveMessageRequest.class))).thenThrow(new IllegalStateException("network")); consumer.poll();
        verify(sqs,never()).deleteMessage(any(DeleteMessageRequest.class));
    }
    @Test void deleteFailureAllowsReplayWithoutUndoingResult() {
        when(work.execute(any(),any())).thenReturn(true);
        when(sqs.deleteMessage(any(DeleteMessageRequest.class))).thenThrow(new IllegalStateException("uncertain delete"));
        consumer.poll(); consumer.poll(); verify(work,times(2)).execute(eq(request),any()); verifyNoInteractions(jobs);
    }
    @Test void noReceiveWithoutCapacityAndShutdownRevokesCurrentLease() {
        when(work.execute(any(),any())).thenAnswer(call->{
            consumer.poll();
            var domain=ProcessingJob.pending(request); domain.acquire(UUID.randomUUID(),NOW,120);
            var lease=((ExecutionLease.Factory)call.getArgument(1)).open(domain.snapshot());
            assertTrue(lease.getAsBoolean()); consumer.close(); assertFalse(lease.getAsBoolean()); return false;
        });
        consumer.poll(); consumer.poll(); verify(sqs,times(1)).receiveMessage(any(ReceiveMessageRequest.class)); verify(future).cancel(false);
    }
    @Test void stopDuringReceiveOrBeforeClaimNeverStartsWork() {
        when(sqs.receiveMessage(any(ReceiveMessageRequest.class))).thenAnswer(call->{consumer.close(); return ReceiveMessageResponse.builder()
                .messages(Message.builder().body(WorkMessageDecoderTest.envelope(request)).build()).build();});
        consumer.poll(); verifyNoInteractions(work);
    }
    @Test void shutdownBetweenClaimAndLeaseDoesNotAck() {
        when(work.execute(any(),any())).thenAnswer(call->{consumer.close();
            ((ExecutionLease.Factory)call.getArgument(1)).open(ProcessingJob.pending(request).snapshot()); return true;});
        consumer.poll(); verify(sqs,never()).deleteMessage(any(DeleteMessageRequest.class));
    }
    @Test void emptyPollAndInvalidQueue() {
        when(sqs.receiveMessage(any(ReceiveMessageRequest.class))).thenReturn(ReceiveMessageResponse.builder().build());
        consumer.poll(); verifyNoInteractions(work);
        assertThrows(IllegalArgumentException.class,()->new WorkConsumer(sqs,"http://other",null,work,jobs,limits(),scheduler,()->true));
        assertThrows(IllegalArgumentException.class,()->new WorkConsumer(sqs,null,null,work,jobs,limits(),scheduler,()->true));
    }
    @Test void insufficientDiskDoesNotReceiveWork() {
        var noDisk=new WorkConsumer(sqs,QUEUE,null,work,jobs,limits(),scheduler,()->false);
        noDisk.poll(); verify(sqs,never()).receiveMessage(any(ReceiveMessageRequest.class));
    }
}
