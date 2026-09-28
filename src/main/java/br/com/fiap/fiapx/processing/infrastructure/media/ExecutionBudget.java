package br.com.fiap.fiapx.processing.infrastructure.media;

import br.com.fiap.fiapx.processing.core.domain.*;
import java.time.Duration;
import java.util.function.BooleanSupplier;

/** One monotonic deadline shared by probe and extraction, never a fresh timeout per command. */
public final class ExecutionBudget {
    private final long start = System.nanoTime();
    private final long nanos;
    private final BooleanSupplier owned;
    public ExecutionBudget(Duration timeout, BooleanSupplier owned) {
        this.nanos = timeout.toNanos(); this.owned = owned;
    }
    public void check() {
        if (Thread.currentThread().isInterrupted() || !owned.getAsBoolean()) {
            throw new IllegalStateException("Media execution no longer owns the job");
        }
        if (System.nanoTime() - start >= nanos) throw new MediaFailure(FailureCode.PROCESSING_TIMEOUT);
    }
}
