package br.com.fiap.fiapx.processing.infrastructure.media;

import br.com.fiap.fiapx.processing.core.domain.*;
import br.com.fiap.fiapx.processing.core.gateway.MediaGateway;
import java.io.*;
import java.nio.file.*;
import java.security.*;
import java.time.Duration;
import java.util.*;
import java.util.concurrent.Semaphore;
import java.util.function.BooleanSupplier;
import java.util.zip.ZipOutputStream;

public final class FfmpegMediaGateway implements MediaGateway {
    private static final String FORMATS = "mov,avi,matroska,webm,asf,flv";
    private final ProcessingLimits limits;
    private final Path root;
    private final String ffmpeg, ffprobe;
    private final MediaProcess process;
    private final Semaphore slot = new Semaphore(1);

    public FfmpegMediaGateway(ProcessingLimits limits, Path root, String ffmpeg, String ffprobe, MediaProcess process) {
        this.limits = limits; this.root = root.toAbsolutePath().normalize();
        this.ffmpeg = ffmpeg; this.ffprobe = ffprobe; this.process = process;
    }

    @Override public LocalMediaResult extract(Path input, BooleanSupplier stillOwned) throws IOException {
        if (!slot.tryAcquire()) throw new IOException("Media capacity unavailable");
        Path directory = null;
        try {
            Path source = input.toRealPath();
            if (!Files.isRegularFile(source) || Files.size(source) == 0 || Files.size(source) > limits.maxInputBytes())
                throw new MediaFailure(FailureCode.INVALID_MEDIA);
            Files.createDirectories(root);
            if (Files.getFileStore(root).getUsableSpace() < limits.diskReserveBytes())
                throw new IOException("Insufficient media disk reservation");
            directory = Files.createTempDirectory(root, "attempt-");
            Path zip = directory.resolve("frames.zip");
            var budget = new ExecutionBudget(Duration.ofSeconds(limits.timeoutSeconds()), stillOwned);
            var report = new ProbeReport(limits.maxDurationSeconds());
            process.run(List.of(ffprobe, "-v", "error", "-threads", "2", "-protocol_whitelist", "file",
                    "-format_whitelist", FORMATS, "-select_streams", "v:0", "-show_frames",
                    "-show_entries", "frame=best_effort_timestamp_time,duration_time:stream=codec_type,avg_frame_rate:format=format_name,duration",
                    "-of", "compact=p=1:nk=0", source.toString()), budget, report::read);
            report.validate();
            var archive = new PngArchive(limits.maxExtractedBytes());
            MessageDigest digest = sha256();
            try (var output = new ZipOutputStream(new LimitedOutput(new DigestOutputStream(
                    Files.newOutputStream(zip, StandardOpenOption.CREATE_NEW), digest), limits.maxZipBytes()))) {
                process.run(List.of(ffmpeg, "-v", "error", "-nostdin", "-xerror", "-threads", "2",
                        "-filter_threads", "2", "-protocol_whitelist", "file", "-format_whitelist", FORMATS,
                        "-i", source.toString(), "-map", "0:v:0", "-an", "-sn", "-dn", "-vf", "fps=1",
                        "-threads", "2", "-c:v", "png", "-f", "image2pipe", "pipe:1"), budget,
                        stream -> archive.write(stream, output, budget));
            }
            budget.check();
            return new Result(directory, zip, Files.size(zip), HexFormat.of().formatHex(digest.digest()), archive.frames());
        } catch (IOException | RuntimeException failure) {
            try { clean(directory); } catch (IOException cleanup) { failure.addSuppressed(cleanup); }
            slot.release(); throw failure;
        }
    }
    private static MessageDigest sha256() {
        try { return MessageDigest.getInstance("SHA-256"); }
        catch (NoSuchAlgorithmException impossible) { throw new IllegalStateException(impossible); }
    }
    private static void clean(Path directory) throws IOException {
        if (directory == null) return;
        Files.deleteIfExists(directory.resolve("frames.zip"));
        Files.deleteIfExists(directory);
    }
    private final class Result implements LocalMediaResult {
        private final Path directory, zip;
        private final long size;
        private final String hash;
        private final int frames;
        private boolean closed;
        Result(Path directory, Path zip, long size, String hash, int frames) {
            this.directory = directory; this.zip = zip; this.size = size; this.hash = hash; this.frames = frames;
        }
        public Path zip() { return zip; }
        public long sizeBytes() { return size; }
        public String sha256() { return hash; }
        public int frameCount() { return frames; }
        public synchronized void close() throws IOException {
            if (!closed) {
                try { clean(directory); }
                finally { closed = true; slot.release(); }
            }
        }
    }
}
