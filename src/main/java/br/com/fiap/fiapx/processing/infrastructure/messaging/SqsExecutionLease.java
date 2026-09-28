package br.com.fiap.fiapx.processing.infrastructure.messaging;

import br.com.fiap.fiapx.processing.core.domain.*;
import br.com.fiap.fiapx.processing.core.gateway.*;
import java.util.concurrent.*;
import java.util.function.LongSupplier;
import software.amazon.awssdk.services.sqs.SqsClient;
import software.amazon.awssdk.services.sqs.model.ChangeMessageVisibilityRequest;

/** Conservative monotonic deadline: network/commit time never extends local ownership. */
public final class SqsExecutionLease implements ExecutionLease {
    private final JobGateway jobs;
    private final JobSnapshot job;
    private final SqsClient sqs;
    private final String queue,receipt;
    private final ProcessingLimits limits;
    private final LongSupplier clock;
    private final ScheduledFuture<?> task;
    private volatile boolean active=true;
    private volatile long deadline;
    public SqsExecutionLease(JobGateway jobs, JobSnapshot job, SqsClient sqs, String queue, String receipt,
            ProcessingLimits limits, ScheduledExecutorService scheduler, LongSupplier clock) {
        this.jobs=jobs; this.job=job; this.sqs=sqs; this.queue=queue; this.receipt=receipt; this.limits=limits; this.clock=clock;
        renew();
        task=scheduler.scheduleWithFixedDelay(this::renew,limits.heartbeatSeconds(),limits.heartbeatSeconds(),TimeUnit.SECONDS);
    }
    synchronized void renew() {
        if (!active) return;
        long start=clock.getAsLong();
        try {
            jobs.heartbeat(job.request().videoId(),job.token());
            sqs.changeMessageVisibility(ChangeMessageVisibilityRequest.builder().queueUrl(queue).receiptHandle(receipt)
                    .visibilityTimeout(Math.toIntExact(limits.leaseSeconds())).build());
            deadline=start+TimeUnit.SECONDS.toNanos(limits.leaseSeconds());
        } catch (RuntimeException failure) { active=false; }
    }
    public boolean getAsBoolean() { return active && clock.getAsLong()-deadline<0; }
    public synchronized void close() { active=false; task.cancel(false); }
}
