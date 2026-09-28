package br.com.fiap.fiapx.processing.infrastructure.messaging;

import br.com.fiap.fiapx.processing.core.domain.ProcessingLimits;
import br.com.fiap.fiapx.processing.core.gateway.JobGateway;
import br.com.fiap.fiapx.processing.core.usecase.ProcessWork;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.atomic.AtomicBoolean;
import org.springframework.scheduling.annotation.Scheduled;
import software.amazon.awssdk.services.sqs.SqsClient;
import software.amazon.awssdk.services.sqs.model.*;

public final class WorkConsumer implements AutoCloseable {
    private static final org.slf4j.Logger LOG=org.slf4j.LoggerFactory.getLogger(WorkConsumer.class);
    private final SqsClient sqs;
    private final String queue;
    private final WorkMessageDecoder decoder;
    private final ProcessWork work;
    private final JobGateway jobs;
    private final ProcessingLimits limits;
    private final ScheduledExecutorService scheduler;
    private final AtomicBoolean busy=new AtomicBoolean();
    private volatile boolean stopped;
    private SqsExecutionLease currentLease;
    private final java.util.function.BooleanSupplier capacity;
    public WorkConsumer(SqsClient sqs,String queue,WorkMessageDecoder decoder,ProcessWork work,JobGateway jobs,
            ProcessingLimits limits,ScheduledExecutorService scheduler,java.util.function.BooleanSupplier capacity) {
        QueueAddress.validate(queue); this.sqs=sqs; this.queue=queue; this.decoder=decoder;
        this.work=work; this.jobs=jobs; this.limits=limits; this.scheduler=scheduler; this.capacity=capacity;
    }
    @Scheduled(fixedDelayString="${messaging.poll-delay-ms:1000}")
    public void poll() {
        if (stopped || !busy.compareAndSet(false,true)) return;
        try {
            if (!capacity.getAsBoolean()) return;
            var received=sqs.receiveMessage(ReceiveMessageRequest.builder().queueUrl(queue).maxNumberOfMessages(1)
                    .waitTimeSeconds(20).visibilityTimeout(Math.toIntExact(limits.leaseSeconds())).build());
            for (var message:received.messages()) {
                if (stopped) return;
                try {
                    var request=decoder.decode(message.body());
                    boolean terminal=work.execute(request,job->openLease(job,message.receiptHandle()));
                    if (terminal) sqs.deleteMessage(DeleteMessageRequest.builder().queueUrl(queue).receiptHandle(message.receiptHandle()).build());
                    else sqs.changeMessageVisibility(ChangeMessageVisibilityRequest.builder().queueUrl(queue)
                            .receiptHandle(message.receiptHandle()).visibilityTimeout(30).build());
                } catch (RuntimeException failure) { LOG.warn("Work delivery deferred; messageId={}",message.messageId()); }
            }
        } catch (RuntimeException failure) { LOG.warn("Work receive unavailable"); }
        finally { busy.set(false); }
    }
    private synchronized SqsExecutionLease openLease(br.com.fiap.fiapx.processing.core.domain.JobSnapshot job,String receipt) {
        if (stopped) throw new IllegalStateException("Consumer stopped");
        currentLease=new SqsExecutionLease(jobs,job,sqs,queue,receipt,limits,scheduler,System::nanoTime);
        return currentLease;
    }
    public synchronized void close() { stopped=true; if (currentLease!=null) currentLease.close(); }
}
