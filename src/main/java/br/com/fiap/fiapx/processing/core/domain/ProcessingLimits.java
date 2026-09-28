package br.com.fiap.fiapx.processing.core.domain;

/** Limits for one active execution; independent of transport receive counts. */
public record ProcessingLimits(long maxInputBytes, long maxDurationSeconds,
                               long maxExtractedBytes, long maxZipBytes,
                               long timeoutSeconds, long maxAttempts,
                               long diskReserveBytes, long leaseSeconds, long heartbeatSeconds) {
    public ProcessingLimits {
        positive(maxInputBytes, "maxInputBytes");
        positive(maxDurationSeconds, "maxDurationSeconds");
        positive(maxExtractedBytes, "maxExtractedBytes");
        positive(maxZipBytes, "maxZipBytes");
        positive(timeoutSeconds, "timeoutSeconds");
        positive(maxAttempts, "maxAttempts");
        if (maxAttempts > 3) throw new IllegalArgumentException("At most three media attempts are supported");
        positive(diskReserveBytes, "diskReserveBytes");
        positive(leaseSeconds, "leaseSeconds");
        positive(heartbeatSeconds, "heartbeatSeconds");
        if (heartbeatSeconds >= leaseSeconds) {
            throw new IllegalArgumentException("Heartbeat must be shorter than the lease");
        }
        long required = Math.addExact(maxInputBytes, Math.addExact(maxExtractedBytes, maxZipBytes));
        if (diskReserveBytes <= required) {
            throw new IllegalArgumentException("Disk reservation must include input, images, ZIP and free space");
        }
    }

    private static void positive(long value, String name) {
        if (value <= 0) throw new IllegalArgumentException(name + " must be positive");
    }
}
