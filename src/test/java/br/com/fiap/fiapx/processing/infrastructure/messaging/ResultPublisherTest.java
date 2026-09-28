package br.com.fiap.fiapx.processing.infrastructure.messaging;

import br.com.fiap.fiapx.processing.infrastructure.persistence.adapter.ProcessingOutbox;
import java.util.*;
import org.junit.jupiter.api.Test;
import software.amazon.awssdk.services.sqs.SqsClient;
import software.amazon.awssdk.services.sqs.model.SendMessageRequest;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;
import static org.mockito.ArgumentMatchers.*;

class ResultPublisherTest {
    final ProcessingOutbox outbox=mock(ProcessingOutbox.class);
    final SqsClient sqs=mock(SqsClient.class);
    final ResultPublisher publisher=new ResultPublisher(outbox,sqs,WorkConsumerTest.QUEUE);
    final ProcessingOutbox.Publication event=new ProcessingOutbox.Publication(UUID.randomUUID(),UUID.randomUUID(),"persisted-envelope");
    @Test void publishesExactStoredEnvelopeBeforeMarkingAndStopsWhenEmpty() {
        when(outbox.claim()).thenReturn(Optional.of(event),Optional.empty()); publisher.dispatch();
        var order=inOrder(outbox,sqs); order.verify(outbox).claim();
        order.verify(sqs).sendMessage(argThat((SendMessageRequest r)->r.messageBody().equals(event.body()) && r.queueUrl().equals(WorkConsumerTest.QUEUE)));
        order.verify(outbox).published(event); verify(outbox,never()).retry(any());
    }
    @Test void uncertainSendAndMarkCommitPreservePayloadForReplay() {
        when(outbox.claim()).thenReturn(Optional.of(event),Optional.empty(),Optional.of(event),Optional.empty());
        when(sqs.sendMessage(any(SendMessageRequest.class))).thenThrow(new IllegalStateException("uncertain send")).thenReturn(null);
        publisher.dispatch(); doThrow(new IllegalStateException("uncertain commit")).when(outbox).published(event);
        publisher.dispatch(); verify(outbox,times(2)).retry(event);
        verify(sqs,times(2)).sendMessage(argThat((SendMessageRequest r)->r.messageBody().equals(event.body())));
    }
    @Test void boundsBatchAndSurvivesDatabaseOutage() {
        when(outbox.claim()).thenReturn(Optional.of(event)); publisher.dispatch(); verify(outbox,times(10)).published(event);
        when(outbox.claim()).thenThrow(new IllegalStateException("db")); assertDoesNotThrow(publisher::dispatch);
        assertThrows(IllegalArgumentException.class,()->new ResultPublisher(outbox,sqs,"file:///queue"));
    }
}
