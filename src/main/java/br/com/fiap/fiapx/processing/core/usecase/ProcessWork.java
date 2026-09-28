package br.com.fiap.fiapx.processing.core.usecase;

import br.com.fiap.fiapx.processing.core.domain.*;
import br.com.fiap.fiapx.processing.core.gateway.*;
import java.io.IOException;

/** A true return is an explicit permission to ACK a durably terminal job. */
public final class ProcessWork {
    private final JobGateway jobs;
    private final MediaGateway media;
    private final StoreProcessingResult storage;
    private final ProcessingLimits limits;
    public ProcessWork(JobGateway jobs, MediaGateway media, StoreProcessingResult storage, ProcessingLimits limits) {
        this.jobs=jobs; this.media=media; this.storage=storage; this.limits=limits;
    }
    public boolean execute(ProcessingRequest request, ExecutionLease.Factory leases) {
        var claim=jobs.acquire(request);
        if (claim.disposition()==JobGateway.Disposition.TERMINAL) return true;
        if (claim.disposition()==JobGateway.Disposition.BUSY) return false;
        var job=claim.job();
        try (var lease=leases.open(job)) {
            if (!lease.getAsBoolean()) return false;
            try {
                // Storage recovery takes precedence over the media attempt budget.
                if (job.result()!=null) return storage.recover(job,lease).status().terminal();
                if (job.mediaAttempts()>=limits.maxAttempts())
                    return jobs.fail(request.videoId(),job.token(),FailureCode.PROCESSING_FAILED).status().terminal();
                var input=storage.download(job,lease);
                if (!lease.getAsBoolean()) return false;
                job=jobs.beginMedia(request.videoId(),job.token());
                MediaGateway.LocalMediaResult result;
                try { result=media.extract(input,lease); }
                catch (MediaFailure failure) {
                    if (!lease.getAsBoolean()) return false;
                    if (failure.code()!=FailureCode.PROCESSING_TIMEOUT && failure.code()!=FailureCode.PROCESSING_FAILED
                            || job.mediaAttempts()>=limits.maxAttempts())
                        return jobs.fail(request.videoId(),job.token(),failure.code()).status().terminal();
                    jobs.defer(request.videoId(),job.token(),30L*job.mediaAttempts());
                    return false;
                }
                try (result) { return storage.publish(job,result,lease).status().terminal(); }
            } catch (IOException | RuntimeException failure) {
                // No ACK on uncertain commit; a later acquire must verify terminal + outbox.
                if (lease.getAsBoolean()) {
                    try { jobs.defer(request.videoId(),job.token(),30L*Math.max(1,job.mediaAttempts())); }
                    catch (RuntimeException uncertainOrLost) { /* Keep durable recovery responsibility. */ }
                }
                return false;
            }
        }
    }
}
