package br.com.fiap.fiapx.processing.infrastructure.storage;

import br.com.fiap.fiapx.processing.core.domain.*;
import br.com.fiap.fiapx.processing.core.gateway.LocalArtifactsGateway;
import java.io.*;
import java.nio.channels.*;
import java.nio.file.*;
import java.util.*;
import java.util.function.*;

/** One private volume per instance. The lock prevents a second process from cleaning its files. */
public final class LocalProcessingArtifacts implements LocalArtifactsGateway, AutoCloseable {
    private final Path root;
    private final long reserve;
    private final FileChannel channel;
    private final FileLock lock;
    public LocalProcessingArtifacts(Path directory, long reserve) throws IOException {
        this.root = directory.toAbsolutePath().normalize(); this.reserve = reserve;
        Files.createDirectories(root);
        channel = FileChannel.open(root.resolve("instance.lock"), StandardOpenOption.CREATE, StandardOpenOption.WRITE);
        try {
            lock = channel.tryLock();
            if (lock == null) throw new IOException("Artifact volume already in use");
        } catch (IOException | RuntimeException failure) { channel.close(); throw failure; }
    }
    public synchronized Path original(JobSnapshot job) throws IOException {
        ensureOpen();
        if (Files.getFileStore(root).getUsableSpace() < reserve) throw new IOException("Insufficient artifact disk reservation");
        return directory(job.request().videoId(), job.token()).resolve("original");
    }
    public synchronized void retain(ResultArtifact artifact, Path source, BooleanSupplier owned) throws IOException {
        Path file = file(artifact);
        if (Files.exists(file, LinkOption.NOFOLLOW_LINKS)) { verify(file, artifact, owned); return; }
        Path partial = file.resolveSibling("frames.zip.part");
        boolean created = false;
        try (var output = FileChannel.open(partial, StandardOpenOption.CREATE_NEW, StandardOpenOption.WRITE)) {
            created = true;
            try (var input = Files.newInputStream(source)) {
                ArtifactBytes.copy(input, Channels.newOutputStream(output), artifact.sizeBytes(), artifact.sha256(), owned);
                output.force(true);
            }
        } catch (IOException | RuntimeException failure) {
            if (created) try { Files.deleteIfExists(partial); } catch (IOException cleanup) { failure.addSuppressed(cleanup); }
            throw failure;
        }
        Files.move(partial, file, StandardCopyOption.ATOMIC_MOVE);
    }
    public synchronized Optional<Path> find(ResultArtifact artifact, BooleanSupplier owned) throws IOException {
        Path file = file(artifact);
        try { Files.readAttributes(file, java.nio.file.attribute.BasicFileAttributes.class, LinkOption.NOFOLLOW_LINKS); }
        catch (NoSuchFileException missing) { return Optional.empty(); }
        verify(file, artifact, owned); return Optional.of(file);
    }
    public synchronized void remove(ResultArtifact artifact) throws IOException {
        var key = ResultKey.parse(artifact.objectKey());
        removeDirectory(directory(key.job(), key.producer()));
    }
    public synchronized void cleanup(BiPredicate<UUID, UUID> eligible) throws IOException {
        ensureOpen();
        try (var entries = Files.list(root)) {
            for (Path directory : entries.toList()) {
                if (!Files.isDirectory(directory, LinkOption.NOFOLLOW_LINKS)) continue;
                String[] parts = directory.getFileName().toString().split("_", -1);
                if (parts.length != 2) continue;
                UUID job, token;
                try { job = UUID.fromString(parts[0]); token = UUID.fromString(parts[1]); }
                catch (IllegalArgumentException unknown) { continue; }
                if (!directory.getFileName().toString().equals(job + "_" + token)) continue;
                if (eligible.test(job, token)) removeDirectory(directory);
            }
        }
    }
    private Path file(ResultArtifact artifact) throws IOException {
        var key = ResultKey.parse(artifact.objectKey());
        return directory(key.job(), key.producer()).resolve("frames.zip");
    }
    private Path directory(UUID job, UUID producer) throws IOException {
        ensureOpen();
        Path directory = root.resolve(job + "_" + producer);
        if (Files.isSymbolicLink(directory)) throw new IOException("Artifact directory cannot be a symbolic link");
        Files.createDirectories(directory); return directory;
    }
    private static void verify(Path file, ResultArtifact artifact, BooleanSupplier owned) throws IOException {
        if (!Files.isRegularFile(file, LinkOption.NOFOLLOW_LINKS)) throw new IOException("Invalid local artifact");
        try (var input = Files.newInputStream(file)) {
            ArtifactBytes.copy(input, OutputStream.nullOutputStream(), artifact.sizeBytes(), artifact.sha256(), owned);
        }
    }
    private static void removeDirectory(Path directory) throws IOException {
        for (String name : List.of("original", "frames.zip.part", "frames.zip")) Files.deleteIfExists(directory.resolve(name));
        Files.deleteIfExists(directory); // Unexpected files are preserved, not recursively removed.
    }
    private void ensureOpen() throws IOException { if (!lock.isValid()) throw new IOException("Artifact volume closed"); }
    public synchronized void close() throws IOException { try { if (lock.isValid()) lock.close(); } finally { channel.close(); } }
}
