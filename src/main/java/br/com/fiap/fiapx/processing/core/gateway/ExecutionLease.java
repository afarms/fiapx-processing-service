package br.com.fiap.fiapx.processing.core.gateway;

import br.com.fiap.fiapx.processing.core.domain.JobSnapshot;
import java.util.function.BooleanSupplier;

public interface ExecutionLease extends BooleanSupplier, AutoCloseable {
    @Override void close();
    interface Factory { ExecutionLease open(JobSnapshot job); }
}
