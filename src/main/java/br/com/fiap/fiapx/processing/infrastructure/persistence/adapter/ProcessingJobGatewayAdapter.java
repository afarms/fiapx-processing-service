package br.com.fiap.fiapx.processing.infrastructure.persistence.adapter;

import br.com.fiap.fiapx.processing.core.domain.*;
import br.com.fiap.fiapx.processing.core.gateway.JobGateway;
import br.com.fiap.fiapx.processing.infrastructure.persistence.entity.ProcessingJobEntity;
import br.com.fiap.fiapx.processing.infrastructure.persistence.mapper.*;
import br.com.fiap.fiapx.processing.infrastructure.persistence.repository.SpringProcessingJobRepository;
import java.time.Instant;
import java.util.*;
import java.util.function.BiConsumer;
import org.springframework.transaction.support.TransactionTemplate;

/** Each returned result has passed TransactionTemplate's commit boundary. */
public final class ProcessingJobGatewayAdapter implements JobGateway {
    private final SpringProcessingJobRepository repository;
    private final ProcessingJobMapper mapper;
    private final ProcessingEventMapper events;
    private final TransactionTemplate transactions;
    private final ProcessingLimits limits;

    public ProcessingJobGatewayAdapter(SpringProcessingJobRepository repository, ProcessingJobMapper mapper,
            ProcessingEventMapper events, TransactionTemplate transactions, ProcessingLimits limits) {
        this.repository = Objects.requireNonNull(repository); this.mapper = Objects.requireNonNull(mapper);
        this.events = Objects.requireNonNull(events); this.transactions = Objects.requireNonNull(transactions);
        this.limits = Objects.requireNonNull(limits);
    }

    public Claim acquire(ProcessingRequest request) {
        return transactions.execute(tx -> {
            String incoming = mapper.request(request);
            repository.insertNew(request.videoId(), request.ownerId(), incoming);
            var entity = lock(request.videoId());
            var job = mapper.read(entity);
            if (!job.snapshot().request().sameWork(request)) throw new IllegalArgumentException("Conflicting job identity");
            repository.insertInbox(request.eventId(), request.videoId(), incoming);
            if (!incoming.equals(repository.inboxRequest(request.eventId()))) throw new IllegalArgumentException("Conflicting event identity");
            Instant now = repository.databaseTime();
            if (job.snapshot().status().terminal()) {
                if (repository.terminalEvents(request.videoId(), job.snapshot().version()) != 1)
                    throw new IllegalStateException("Terminal outbox missing");
                repository.finishInbox(request.videoId(), now);
                return new Claim(Disposition.TERMINAL, job.snapshot());
            }
            if (!job.acquire(UUID.randomUUID(), now, limits.leaseSeconds())) return new Claim(Disposition.BUSY, job.snapshot());
            repository.endAttempts(request.videoId(), now);
            var state = save(job, entity);
            repository.insertAttempt(state.token(), request.videoId(), state.attempt(), now, state.leaseUntil());
            enqueue("ProcessingStarted", state, now);
            return new Claim(Disposition.ACQUIRED, state);
        });
    }

    private ProcessingJobEntity lock(UUID id) {
        return repository.lock(id).orElseThrow(() -> new IllegalArgumentException("Job not found"));
    }

    private JobSnapshot save(ProcessingJob job, ProcessingJobEntity entity) {
        var state = job.snapshot(); mapper.write(state, entity); repository.saveAndFlush(entity); return state;
    }

    private JobSnapshot update(UUID id, BiConsumer<ProcessingJob, Instant> mutation) {
        return transactions.execute(tx -> {
            var entity = lock(id); var job = mapper.read(entity);
            mutation.accept(job, repository.databaseTime());
            return save(job, entity);
        });
    }

    public JobSnapshot heartbeat(UUID id, UUID token) {
        return update(id, (job, now) -> {
            job.heartbeat(token, now, limits.leaseSeconds()); repository.renewAttempt(token, job.snapshot().leaseUntil());
        });
    }

    public JobSnapshot beginMedia(UUID id, UUID token) {
        return update(id, (job, now) -> {
            job.beginMedia(token, now, (int) limits.maxAttempts()); repository.startMedia(token, now);
        });
    }

    public JobSnapshot prepareResult(UUID id, UUID token, ResultArtifact result) {
        return update(id, (job, now) -> {
            job.prepareResult(token, now, result, limits.maxZipBytes());
            repository.resultIntent(token, id, mapper.result(result));
        });
    }

    public JobSnapshot discardMissingResult(UUID id, UUID token) {
        return update(id, (job, now) -> job.discardMissingResult(token, now));
    }

    public JobSnapshot defer(UUID id, UUID token, long delaySeconds) {
        return update(id, (job, now) -> {
            job.defer(token, now, delaySeconds); repository.endAttempts(id, now);
        });
    }

    public JobSnapshot complete(UUID id, UUID token) { return terminal(id, token, null); }
    public JobSnapshot fail(UUID id, UUID token, FailureCode code) { return terminal(id, token, Objects.requireNonNull(code)); }

    private JobSnapshot terminal(UUID id, UUID token, FailureCode failure) {
        return transactions.execute(tx -> {
            var entity = lock(id); var job = mapper.read(entity); Instant now = repository.databaseTime();
            if (failure == null) job.complete(token, now); else job.fail(token, now, failure);
            var state = save(job, entity);
            enqueue(failure == null ? "ProcessingCompleted" : "ProcessingFailed", state, now);
            repository.finishInbox(id, now); repository.endAttempts(id, now);
            return state;
        });
    }

    private void enqueue(String type, JobSnapshot state, Instant now) {
        UUID eventId = UUID.randomUUID();
        repository.enqueue(eventId, state.request().videoId(), state.version(), type,
                events.envelope(eventId, type, state, now), now);
    }
}
