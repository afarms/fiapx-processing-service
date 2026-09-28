package br.com.fiap.fiapx.processing.infrastructure.messaging;

import br.com.fiap.fiapx.processing.infrastructure.persistence.adapter.ProcessingOutbox;
import org.springframework.scheduling.annotation.Scheduled;
import software.amazon.awssdk.services.sqs.SqsClient;
import software.amazon.awssdk.services.sqs.model.SendMessageRequest;

public final class ResultPublisher {
    private static final org.slf4j.Logger LOG=org.slf4j.LoggerFactory.getLogger(ResultPublisher.class);
    private final ProcessingOutbox outbox;
    private final SqsClient sqs;
    private final String queue;
    public ResultPublisher(ProcessingOutbox outbox,SqsClient sqs,String queue) {
        QueueAddress.validate(queue); this.outbox=outbox; this.sqs=sqs; this.queue=queue;
    }
    @Scheduled(fixedDelayString="${messaging.publisher-delay-ms:5000}")
    public void dispatch() {
        try {
            for (int count=0;count<10;count++) {
                var claimed=outbox.claim();
                if (claimed.isEmpty()) return;
                var event=claimed.get();
                try {
                    sqs.sendMessage(SendMessageRequest.builder().queueUrl(queue).messageBody(event.body()).build());
                    outbox.published(event);
                } catch (RuntimeException failure) {
                    LOG.warn("Result publication deferred; eventId={}",event.id());
                    outbox.retry(event);
                }
            }
        } catch (RuntimeException failure) { LOG.warn("Result outbox unavailable"); }
    }
}
