package br.com.fiap.fiapx.processing.core.domain;

import static br.com.fiap.fiapx.processing.core.domain.ProcessingRequest.require;

public record ResultArtifact(String bucket, String objectKey, long sizeBytes, String sha256, int frameCount) {
    public ResultArtifact {
        require(bucket != null && !bucket.isBlank(), "Missing result bucket");
        require(objectKey != null && !objectKey.isBlank(), "Missing result key");
        require(sizeBytes > 0, "Empty result");
        require(sha256 != null && sha256.matches("[0-9a-f]{64}"), "Invalid result digest");
        require(frameCount > 0, "No images produced");
    }
}
