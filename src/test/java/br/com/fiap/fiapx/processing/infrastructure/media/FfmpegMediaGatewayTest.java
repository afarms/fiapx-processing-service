package br.com.fiap.fiapx.processing.infrastructure.media;

import br.com.fiap.fiapx.processing.core.domain.*;
import java.io.*;
import java.nio.file.*;
import java.security.MessageDigest;
import java.util.*;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import static org.junit.jupiter.api.Assertions.*;

class FfmpegMediaGatewayTest {
    @TempDir Path temporary;
    private Path input() throws IOException { return Files.write(temporary.resolve("user ; name.mp4"), new byte[]{1}); }
    private FfmpegMediaGateway gateway(MediaProcess process) {
        return new FfmpegMediaGateway(MediaFixtures.limits(100000, 100000), temporary.resolve("work"), "ffmpeg", "ffprobe", process);
    }
    private static MediaProcess successful(List<List<String>> commands) {
        return (command, budget, reader) -> {
            commands.add(command); budget.check();
            reader.read(new ByteArrayInputStream(command.getFirst().equals("ffprobe")
                    ? MediaFixtures.PROBE.getBytes(java.nio.charset.StandardCharsets.UTF_8) : MediaFixtures.png()));
        };
    }
    @Test void returnsCompleteHashedArchiveWithExclusiveAdmissionUntilReleased() throws Exception {
        var commands = new ArrayList<List<String>>(); var gateway = gateway(successful(commands)); Path input = input();
        Path completed;
        try (var result = gateway.extract(input, () -> true)) {
            completed = result.zip(); assertTrue(Files.exists(completed)); assertEquals(1, result.frameCount());
            assertEquals(Files.size(completed), result.sizeBytes());
            assertEquals(HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(Files.readAllBytes(completed))), result.sha256());
            assertThrows(IOException.class, () -> gateway.extract(input, () -> true));
            String realInput = input.toRealPath().toString();
            assertTrue(commands.stream().allMatch(c -> c.contains(realInput)));
            assertEquals("file", commands.getFirst().get(commands.getFirst().indexOf("-protocol_whitelist") + 1));
            assertTrue(commands.getLast().contains("fps=1")); assertTrue(commands.getLast().contains("pipe:1"));
            result.close(); result.close();
        }
        assertFalse(Files.exists(completed)); assertTrue(Files.exists(input));
        try (var next = gateway.extract(input, () -> true)) { assertEquals(1, next.frameCount()); }
    }
    @Test void cleanupFailureReleasesCapacityExactlyOnceAndPreservesTheError() throws Exception {
        var gateway = gateway(successful(new ArrayList<>()));
        Path input = input();
        var result = gateway.extract(input, () -> true);
        Path unexpected = Files.writeString(result.zip().getParent().resolve("unexpected.txt"), "preserve");

        assertThrows(DirectoryNotEmptyException.class, () -> {
            try (result) { assertTrue(Files.exists(result.zip())); }
        });
        assertTrue(Files.exists(unexpected));
        try (var next = gateway.extract(input, () -> true)) {
            assertEquals(1, next.frameCount());
            result.close();
            result.close();
            assertThrows(IOException.class, () -> gateway.extract(input, () -> true));
        }
        try (var following = gateway.extract(input, () -> true)) { assertEquals(1, following.frameCount()); }
    }

    @Test void cleansPartialsOnMediaOrDependencyFailureAndDoesNotTouchOtherAttempts() throws Exception {
        Path input = input(), other = temporary.resolve("work/attempt-other"); Files.createDirectories(other);
        Files.writeString(other.resolve("frames.zip"), "active");
        for (boolean io : new boolean[]{true, false}) {
            var gateway = gateway((command, budget, reader) -> {
                if (command.getFirst().equals("ffprobe")) reader.read(new ByteArrayInputStream(MediaFixtures.PROBE.getBytes()));
                else { reader.read(new ByteArrayInputStream(MediaFixtures.png()));
                    if (io) throw new IOException("disk unavailable");
                    throw new MediaFailure(FailureCode.INVALID_MEDIA);
                }
            });
            if (io) assertThrows(IOException.class, () -> gateway.extract(input, () -> true));
            else assertThrows(MediaFailure.class, () -> gateway.extract(input, () -> true));
            try (var paths = Files.list(other.getParent())) { assertEquals(List.of(other), paths.toList()); }
        }
        assertEquals("active", Files.readString(other.resolve("frames.zip")));
    }
    @Test void rejectsInvalidInputAndLostOwnershipWithoutPublishingResult() throws Exception {
        var gateway = gateway(successful(new ArrayList<>()));
        Path input = input();
        assertThrows(IllegalStateException.class, () -> gateway.extract(input, () -> false));
        assertThrows(IOException.class, () -> gateway.extract(temporary.resolve("missing"), () -> true));
        assertThrows(MediaFailure.class, () -> gateway.extract(temporary, () -> true));
        Files.write(input, new byte[0]);
        assertThrows(MediaFailure.class, () -> gateway.extract(input, () -> true));
        try (var file = new RandomAccessFile(input.toFile(), "rw")) { file.setLength(100000001); }
        assertThrows(MediaFailure.class, () -> gateway.extract(input, () -> true));
        var noDisk = new ProcessingLimits(1, 300, 1, 1, 600, 3, Long.MAX_VALUE, 120, 30);
        Files.write(input, new byte[]{1});
        var full = new FfmpegMediaGateway(noDisk, temporary, "ffmpeg", "ffprobe", successful(new ArrayList<>()));
        assertThrows(IOException.class, () -> full.extract(input, () -> true));
    }
}
