package br.com.fiap.fiapx.processing.infrastructure.media;

import br.com.fiap.fiapx.processing.core.domain.*;
import java.io.*;
import java.util.List;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicBoolean;

/** Argument lists only. No shell or unbounded subprocess logs. */
public final class LocalMediaProcess implements MediaProcess {
    @Override public void run(List<String> command, ExecutionBudget budget, OutputReader reader) throws IOException {
        budget.check();
        Process process = new ProcessBuilder(command).redirectError(ProcessBuilder.Redirect.DISCARD).start();
        try (var executor = Executors.newVirtualThreadPerTaskExecutor()) {
            var ended = new AtomicBoolean();
            var stdout = new FilterInputStream(process.getInputStream()) {
                @Override public int read() throws IOException {
                    int value = super.read(); if (value == -1) ended.set(true); return value;
                }
                @Override public int read(byte[] bytes, int offset, int length) throws IOException {
                    int count = super.read(bytes, offset, length); if (count == -1) ended.set(true); return count;
                }
            };
            Future<?> output = executor.submit(() -> { reader.read(stdout); return null; });
            try {
                process.getOutputStream().close();
                Throwable readerFailure = null;
                while (true) {
                    budget.check();
                    try { output.get(50, TimeUnit.MILLISECONDS); break; }
                    catch (TimeoutException waiting) { /* deadline and ownership checked on each poll */ }
                    catch (ExecutionException failure) { readerFailure = failure.getCause(); break; }
                }
                if (readerFailure != null) {
                    // An OOM-killed encoder can close stdout in the middle of a PNG. Preserve
                    // the operational exit instead of misclassifying truncation as bad input.
                    // EOF can precede the OS exit notification. Wait for that notification
                    // under the same budget instead of guessing after a fixed grace period.
                    if (ended.get()) while (!process.waitFor(50, TimeUnit.MILLISECONDS)) budget.check();
                    if (!process.isAlive() && process.exitValue() == 137)
                        throw new IOException("Media process terminated by resource exhaustion");
                    propagate(readerFailure);
                }
                while (!process.waitFor(50, TimeUnit.MILLISECONDS)) budget.check();
                budget.check();
                int exit = process.exitValue();
                if (exit == 137) throw new IOException("Media process terminated by resource exhaustion");
                if (exit != 0) throw new MediaFailure(FailureCode.INVALID_MEDIA);
            } catch (InterruptedException interrupted) {
                Thread.currentThread().interrupt();
                throw new IOException("Media execution interrupted", interrupted);
            } finally {
                stop(process);
                output.cancel(true);
            }
        }
    }

    private static void propagate(Throwable cause) throws IOException {
        if (cause instanceof IOException io) throw io;
        if (cause instanceof RuntimeException runtime) throw runtime;
        throw new IOException("Media output reader failed", cause);
    }

    private static void stop(Process process) throws IOException {
        var descendants = process.descendants().toList();
        descendants.forEach(ProcessHandle::destroyForcibly);
        process.destroyForcibly();
        // Kill first: closing stdout while a reader blocks can otherwise deadlock.
        try {
            if (!process.waitFor(5, TimeUnit.SECONDS)) throw new IOException("Media process did not terminate");
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
        } finally { process.getInputStream().close(); }
    }
}
