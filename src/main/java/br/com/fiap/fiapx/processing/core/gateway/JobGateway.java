package br.com.fiap.fiapx.processing.core.gateway;

import br.com.fiap.fiapx.processing.core.domain.*;
import java.util.UUID;

public interface JobGateway {
    enum Disposition { ACQUIRED, BUSY, TERMINAL }
    /** TERMINAL is returned only after checking a durable terminal outbox entry. */
    record Claim(Disposition disposition, JobSnapshot job) {}
    Claim acquire(ProcessingRequest request);
    JobSnapshot heartbeat(UUID jobId, UUID token);
    JobSnapshot beginMedia(UUID jobId, UUID token);
    JobSnapshot prepareResult(UUID jobId, UUID token, ResultArtifact result);
    JobSnapshot discardMissingResult(UUID jobId, UUID token);
    JobSnapshot defer(UUID jobId, UUID token, long delaySeconds);
    JobSnapshot complete(UUID jobId, UUID token);
    JobSnapshot fail(UUID jobId, UUID token, FailureCode code);
}
