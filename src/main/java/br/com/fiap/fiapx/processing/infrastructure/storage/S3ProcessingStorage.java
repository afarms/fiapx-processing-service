package br.com.fiap.fiapx.processing.infrastructure.storage;

import br.com.fiap.fiapx.processing.core.domain.*;
import br.com.fiap.fiapx.processing.core.gateway.ObjectStorageGateway;
import java.io.*;
import java.nio.file.*;
import java.util.*;
import java.util.function.BooleanSupplier;
import software.amazon.awssdk.core.sync.RequestBody;
import software.amazon.awssdk.services.s3.S3Client;
import software.amazon.awssdk.services.s3.model.*;

public final class S3ProcessingStorage implements ObjectStorageGateway {
    private final S3Client client;
    private final String bucket;
    private final long maxZip;
    public S3ProcessingStorage(S3Client client, String bucket, long maxZip) {
        if (!bucket.matches("[a-z0-9][a-z0-9.-]{1,61}[a-z0-9]") || maxZip <= 0)
            throw new IllegalArgumentException("Invalid storage configuration");
        this.client = client; this.bucket = bucket; this.maxZip = maxZip;
    }
    public void download(ProcessingRequest request, Path target, BooleanSupplier owned) throws IOException {
        allowed(request.bucket());
        ArtifactBytes.check(owned);
        // CREATE_NEW avoids replacing an original still used by another execution.
        try (var output = Files.newOutputStream(target, StandardOpenOption.CREATE_NEW)) {
            try { read(request.objectKey(), request.sizeBytes(), request.sha256(), output, owned); }
            catch (IOException | RuntimeException failure) {
                try { output.close(); Files.deleteIfExists(target); } catch (IOException cleanup) { failure.addSuppressed(cleanup); }
                throw failure;
            }
        }
    }
    public boolean present(ResultArtifact artifact, BooleanSupplier owned) throws IOException {
        validate(artifact);
        try { read(artifact.objectKey(), artifact.sizeBytes(), artifact.sha256(), OutputStream.nullOutputStream(), owned); return true; }
        catch (MissingObject missing) { return false; }
    }
    private void read(String key, long bytes, String hash, OutputStream output, BooleanSupplier owned) throws IOException {
        ArtifactBytes.check(owned);
        try (var stream = client.getObject(GetObjectRequest.builder().bucket(bucket).key(key).build())) {
            try {
                if (!Objects.equals(stream.response().contentLength(), bytes)) throw new IOException("Artifact size mismatch");
                ArtifactBytes.copy(stream, output, bytes, hash, owned);
            } finally { stream.abort(); } // Never drain an oversized/rejected object on close.
        } catch (S3Exception failure) {
            if (failure.statusCode() == 404 && "NoSuchKey".equals(failure.awsErrorDetails() == null ? null : failure.awsErrorDetails().errorCode()))
                throw new MissingObject();
            throw new IOException("Object storage read unavailable", failure);
        } catch (RuntimeException failure) { throw new IOException("Object storage read unavailable", failure); }
    }
    public void store(ResultArtifact artifact, Path file, BooleanSupplier owned) throws IOException {
        validate(artifact);
        try (var input = Files.newInputStream(file)) {
            ArtifactBytes.copy(input, OutputStream.nullOutputStream(), artifact.sizeBytes(), artifact.sha256(), owned);
        }
        try {
            client.putObject(PutObjectRequest.builder().bucket(bucket).key(artifact.objectKey()).ifNoneMatch("*")
                    .contentType("application/zip").checksumSHA256(Base64.getEncoder().encodeToString(HexFormat.of().parseHex(artifact.sha256())))
                    .build(), RequestBody.fromFile(file));
        } catch (S3Exception failure) {
            if (failure.statusCode() != 409 && failure.statusCode() != 412) throw new IOException("Object storage write uncertain", failure);
        } catch (RuntimeException failure) { throw new IOException("Object storage write uncertain", failure); }
        if (!present(artifact, owned)) throw new IOException("Result not confirmed after write");
    }
    public void deleteAbandoned(ResultArtifact artifact) throws IOException {
        validate(artifact);
        try { client.deleteObject(DeleteObjectRequest.builder().bucket(bucket).key(artifact.objectKey()).build()); }
        catch (RuntimeException failure) { throw new IOException("Object cleanup uncertain", failure); }
    }
    private void allowed(String candidate) {
        if (!bucket.equals(candidate)) throw new IllegalArgumentException("Unexpected media bucket");
    }
    private void validate(ResultArtifact artifact) {
        allowed(artifact.bucket()); ResultKey.parse(artifact.objectKey());
        if (artifact.sizeBytes() > maxZip) throw new IllegalArgumentException("Result exceeds storage limit");
    }
    private static final class MissingObject extends IOException {}
}
