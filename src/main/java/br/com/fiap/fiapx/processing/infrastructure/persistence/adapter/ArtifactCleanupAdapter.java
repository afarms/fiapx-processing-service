package br.com.fiap.fiapx.processing.infrastructure.persistence.adapter;

import br.com.fiap.fiapx.processing.core.domain.*;
import br.com.fiap.fiapx.processing.core.gateway.ArtifactCleanupGateway;
import br.com.fiap.fiapx.processing.infrastructure.persistence.mapper.ProcessingJobMapper;
import br.com.fiap.fiapx.processing.infrastructure.persistence.repository.SpringProcessingJobRepository;
import java.util.*;
import org.springframework.transaction.support.TransactionTemplate;

public final class ArtifactCleanupAdapter implements ArtifactCleanupGateway {
    private final SpringProcessingJobRepository repository;
    private final ProcessingJobMapper mapper;
    private final TransactionTemplate tx;
    public ArtifactCleanupAdapter(SpringProcessingJobRepository repository, ProcessingJobMapper mapper, TransactionTemplate tx) {
        this.repository = repository; this.mapper = mapper; this.tx = tx;
    }
    public List<ResultArtifact> abandoned(int limit) {
        if (limit < 1 || limit > 1000) throw new IllegalArgumentException("Invalid cleanup batch");
        return repository.abandonedResults(limit).stream().map(mapper::readResult).toList();
    }
    public boolean remoteAllowed(UUID job, UUID producer) { return allowed(job, producer, false); }
    public boolean localAllowed(UUID job, UUID producer) { return allowed(job, producer, true); }
    private boolean allowed(UUID job, UUID producer, boolean local) {
        return Boolean.TRUE.equals(tx.execute(status -> {
            // Serialize with prepareResult/complete: never delete from an unlocked stale snapshot.
            var row = repository.lock(job);
            if (row.isEmpty() || repository.attemptBelongs(job, producer) != 1) return false;
            var state = mapper.read(row.get()).snapshot();
            if (!state.status().terminal() && producer.equals(state.token())
                    && state.leaseUntil().isAfter(repository.databaseTime())) return false;
            if (state.result() != null && producer.equals(ResultKey.parse(state.result().objectKey()).producer()))
                return local && state.status().terminal();
            return true;
        }));
    }
    public void checked(UUID producer) { tx.executeWithoutResult(status -> repository.checkedResult(producer)); }
}
