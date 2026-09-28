package br.com.fiap.fiapx.processing.infrastructure.storage;

import java.io.*;
import java.nio.channels.OverlappingFileLockException;
import java.nio.file.*;
import java.util.*;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import static org.junit.jupiter.api.Assertions.*;
import static br.com.fiap.fiapx.processing.infrastructure.storage.StorageFixtures.*;

class LocalProcessingArtifactsTest {
    @TempDir Path temporary;
    @Test void retainedArtifactSurvivesRestartAndCannotBeOverwritten() throws Exception {
        Path root = temporary.resolve("artifacts"), source = Files.write(temporary.resolve("source"), BYTES);
        var job = job().snapshot(); var artifact = artifact(job);
        try (var files = new LocalProcessingArtifacts(root, 1)) {
            assertThrows(OverlappingFileLockException.class, () -> new LocalProcessingArtifacts(root, 1));
            Files.write(files.original(job), BYTES);
            assertTrue(files.find(artifact, () -> true).isEmpty());
            files.retain(artifact, source, () -> true); files.retain(artifact, source, () -> true);
            assertArrayEquals(BYTES, Files.readAllBytes(files.find(artifact, () -> true).orElseThrow()));
            files.cleanup((id, token) -> false); assertTrue(files.find(artifact, () -> true).isPresent());
        }
        try (var restarted = new LocalProcessingArtifacts(root, 1)) {
            assertArrayEquals(BYTES, Files.readAllBytes(restarted.find(artifact, () -> true).orElseThrow()));
            restarted.remove(artifact); assertTrue(restarted.find(artifact, () -> true).isEmpty());
            restarted.cleanup((id, token) -> true);
        }
    }
    @Test void cleansOnlyEligibleOwnedDirectoriesAndPreservesUnexpectedFiles() throws Exception {
        Path root = temporary.resolve("root"), source = Files.write(temporary.resolve("source"), BYTES);
        var job = job().snapshot(); var artifact = artifact(job);
        try (var files = new LocalProcessingArtifacts(root, 1)) {
            files.retain(artifact, source, () -> true);
            Path retained = files.find(artifact, () -> true).orElseThrow(), unexpected = retained.resolveSibling("unknown");
            Files.writeString(unexpected, "preserve");
            assertThrows(DirectoryNotEmptyException.class, () -> files.cleanup((id, token) -> true));
            assertTrue(Files.exists(unexpected));
            Files.delete(unexpected);
            Files.createDirectories(root.resolve("other-instance")); Files.createDirectories(root.resolve("bad_uuid"));
            Files.createDirectories(root.resolve("1-1-1-1-1_1-1-1-1-1"));
            files.cleanup((id, token) -> true);
            assertTrue(Files.isDirectory(root.resolve("other-instance")));
            assertFalse(Files.exists(retained.getParent()));
        }
    }
    @Test void failsClosedOnCorruptionLostLeaseDiskPressureAndClosedVolume() throws Exception {
        Path root = temporary.resolve("root"), source = Files.write(temporary.resolve("source"), new byte[]{1});
        var job = job().snapshot(); var artifact = artifact(job);
        var files = new LocalProcessingArtifacts(root, Long.MAX_VALUE);
        assertThrows(IOException.class, () -> files.original(job));
        assertThrows(IOException.class, () -> files.retain(artifact, source, () -> true));
        assertThrows(IOException.class, () -> files.retain(artifact, source, () -> false));
        Files.write(source, BYTES); files.retain(artifact, source, () -> true);
        Path retained = files.find(artifact, () -> true).orElseThrow(); Files.write(retained, new byte[]{2});
        assertThrows(IOException.class, () -> files.find(artifact, () -> true));
        Thread.currentThread().interrupt();
        try { assertThrows(IOException.class, () -> ArtifactBytes.check(() -> true)); } finally { Thread.interrupted(); }
        files.close(); assertThrows(IOException.class, () -> files.original(job));
        files.close();
    }
    @Test void existingPartialIsPreservedUntilItsAttemptIsEligibleForCleanup() throws Exception {
        var job=job().snapshot(); var artifact=artifact(job);
        Path root=temporary.resolve("partials"), source=Files.write(temporary.resolve("source"),BYTES);
        try(var files=new LocalProcessingArtifacts(root,1)) {
            Path partial=files.original(job).resolveSibling("frames.zip.part"); Files.write(partial,BYTES);
            assertThrows(FileAlreadyExistsException.class,()->files.retain(artifact,source,()->true));
            assertArrayEquals(BYTES,Files.readAllBytes(partial));
            files.cleanup((id,token)->true); assertFalse(Files.exists(partial));
        }
    }
}
