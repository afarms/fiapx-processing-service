package br.com.fiap.fiapx.harness;

import java.io.IOException;
import java.nio.file.*;
import java.time.Duration;
import java.time.Instant;
import java.util.Set;
import java.util.UUID;

/** File controls for the dedicated test image. Never included in the application jar. */
public final class FaultControl {
    public static final Set<String> POINTS = Set.of("before-download", "before-media", "after-put", "before-ack");
    private final Path root;
    private final String worker;
    private final Duration timeout;

    public FaultControl(Path root, String worker, Duration timeout) {
        if (!Set.of("worker-a", "worker-b").contains(worker) || timeout.isNegative() || timeout.isZero())
            throw new IllegalArgumentException("Invalid harness configuration");
        this.root = root.toAbsolutePath().normalize(); this.worker = worker; this.timeout = timeout;
    }

    public void hit(String point, UUID video) {
        if (!POINTS.contains(point)) throw new IllegalArgumentException("Unknown fault point");
        Path base = base(point, video);
        Path arm = Path.of(base + ".arm");
        try {
            if (!Files.exists(arm)) return;
            // Only IDs explicitly enrolled by the controller are eligible for fault injection.
            if (!Files.isRegularFile(root.resolve("allowed").resolve(video.toString())))
                throw new IllegalStateException("Fault target absent from manifest");
            Path claimed = Path.of(base + ".claimed");
            // An existing claim is never reused, including across a process restart.
            try { Files.createFile(claimed); } catch (FileAlreadyExistsException alreadyUsed) { return; }
            String mode = Files.readString(arm).strip();
            if (!Set.of("pause", "fail").contains(mode)) throw new IllegalStateException("Invalid fault mode");
            trace(point + "-reached", video);
            if (mode.equals("fail")) throw new IllegalStateException("Injected harness failure: " + point);
            long start = System.nanoTime();
            while (!Files.exists(Path.of(base + ".release"))) {
                if (System.nanoTime() - start >= timeout.toNanos())
                    throw new IllegalStateException("Harness pause expired: " + point);
                try { Thread.sleep(50); }
                catch (InterruptedException interrupted) {
                    Thread.currentThread().interrupt(); throw new IllegalStateException("Harness pause interrupted");
                }
            }
            trace(point + "-released", video);
        } catch (IOException failure) { throw new IllegalStateException("Harness control unavailable", failure); }
    }

    public synchronized void trace(String event, UUID video) {
        if (video == null) return;
        try {
            Files.createDirectories(root.resolve("events"));
            Files.writeString(root.resolve("events").resolve(worker + ".tsv"),
                    Instant.now() + "\t" + worker + "\t" + event + "\t" + video + "\n",
                    StandardOpenOption.CREATE, StandardOpenOption.APPEND);
        } catch (IOException failure) { throw new IllegalStateException("Harness evidence unavailable", failure); }
    }

    private Path base(String point, UUID video) { return root.resolve(worker + "." + video + "." + point); }
}
