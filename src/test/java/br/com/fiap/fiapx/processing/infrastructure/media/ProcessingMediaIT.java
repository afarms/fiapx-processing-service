package br.com.fiap.fiapx.processing.infrastructure.media;

import br.com.fiap.fiapx.processing.core.domain.*;
import java.io.*;
import java.nio.file.*;
import java.time.Duration;
import java.util.*;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.zip.ZipFile;
import javax.imageio.ImageIO;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import static org.junit.jupiter.api.Assertions.*;

class ProcessingMediaIT {
    @TempDir Path temporary;
    private static final String FFMPEG = "/usr/bin/ffmpeg", FFPROBE = "/usr/bin/ffprobe";
    private FfmpegMediaGateway gateway(long png, long zip, long timeout, MediaProcess process) {
        var limits = new ProcessingLimits(100000000, 300, png, zip, timeout, 3, 3221225472L, 120, 30);
        return new FfmpegMediaGateway(limits, temporary.resolve("work"), FFMPEG, FFPROBE, process);
    }
    private Path video(String extension, String codec, String duration, String rate) throws Exception {
        Path file = temporary.resolve(UUID.randomUUID() + " ; fixture." + extension);
        new LocalMediaProcess().run(List.of(FFMPEG, "-v", "error", "-nostdin", "-f", "lavfi", "-i",
                "testsrc2=size=64x48:rate=" + rate + ":duration=" + duration,
                "-threads", "2", "-c:v", codec, "-y", file.toString()),
                new ExecutionBudget(Duration.ofSeconds(30), () -> true), InputStream::readAllBytes);
        return file;
    }
    @ParameterizedTest
    @CsvSource({"mp4,mpeg4", "avi,mpeg4", "mov,mpeg4", "mkv,ffv1", "wmv,wmv2", "flv,flv1", "webm,libvpx-vp9"})
    void sevenRealContainersProduceValidPngsAtOneFramePerSecond(String extension, String codec) throws Exception {
        Path input = video(extension, codec, "2", "5");
        var gateway = gateway(1073741824, 1073741824, 600, new LocalMediaProcess());
        Path output;
        try (var result = gateway.extract(input, () -> true)) {
            output = result.zip(); assertEquals(2, result.frameCount());
            try (var zip = new ZipFile(output.toFile())) {
                assertEquals(2, zip.size());
                for (var entries = zip.entries(); entries.hasMoreElements();) {
                    var entry = entries.nextElement();
                    assertTrue(entry.getName().matches("frame-\\d{6}\\.png"));
                    try (var stream = zip.getInputStream(entry)) {
                        var image = ImageIO.read(stream); assertNotNull(image);
                        assertEquals(64, image.getWidth()); assertEquals(48, image.getHeight());
                    }
                }
            }
        }
        assertFalse(Files.exists(output)); assertTrue(Files.exists(input)); assertClean();
    }
    @Test void acceptsThreeHundredSecondsAndRejectsTheNextDecodedFrame() throws Exception {
        var gateway = gateway(1073741824, 1073741824, 600, new LocalMediaProcess());
        try (var result = gateway.extract(video("mkv", "ffv1", "300", "1"), () -> true)) {
            assertEquals(300, result.frameCount());
        }
        Path excessive = video("mkv", "ffv1", "300.5", "2");
        assertEquals(FailureCode.DURATION_EXCEEDED,
                assertThrows(MediaFailure.class, () -> gateway.extract(excessive, () -> true)).code());
        assertClean();
    }
    @Test void decodesStreamingMatroskaWithoutDeclaredDuration() throws Exception {
        Path input = temporary.resolve("streamed.mkv");
        var process = new LocalMediaProcess();
        process.run(List.of(FFMPEG, "-v", "error", "-f", "lavfi", "-i", "testsrc2=size=64x48:rate=5:duration=2",
                "-threads", "2", "-c:v", "ffv1", "-f", "matroska", "pipe:1"), MediaFixtures.budget(),
                stream -> Files.copy(stream, input));
        process.run(List.of(FFPROBE, "-v", "error", "-show_entries", "format=duration", "-of", "compact", input.toString()),
                MediaFixtures.budget(), stream -> assertTrue(new String(stream.readAllBytes()).contains("duration=N/A")));
        try (var result = gateway(1000000, 1000000, 600, process).extract(input, () -> true)) {
            assertEquals(2, result.frameCount());
        }
        assertClean();
    }
    @Test void enforcesPngAndZipQuotasAtExactByteBoundaryIncludingCurrentImage() throws Exception {
        Path input = video("mp4", "mpeg4", "2", "5");
        long pngBytes, zipBytes;
        try (var result = gateway(1073741824, 1073741824, 600, new LocalMediaProcess()).extract(input, () -> true);
             var zip = new ZipFile(result.zip().toFile())) {
            pngBytes = zip.stream().mapToLong(java.util.zip.ZipEntry::getSize).sum(); zipBytes = result.sizeBytes();
        }
        try (var result = gateway(pngBytes, zipBytes, 600, new LocalMediaProcess()).extract(input, () -> true)) {
            assertEquals(zipBytes, result.sizeBytes());
        }
        for (long[] quota : new long[][]{{pngBytes - 1, zipBytes}, {pngBytes, zipBytes - 1}, {10, zipBytes}}) {
            assertEquals(FailureCode.OUTPUT_LIMIT_EXCEEDED, assertThrows(MediaFailure.class,
                    () -> gateway(quota[0], quota[1], 600, new LocalMediaProcess()).extract(input, () -> true)).code());
            assertClean();
        }
    }
    @Test void rejectsInvalidContentAudioOnlyAndRemotePlaylists() throws Exception {
        Path invalid = Files.writeString(temporary.resolve("invalid.mp4"), "invalid media");
        Path playlist = Files.writeString(temporary.resolve("playlist.mp4"),
                "#EXTM3U\n#EXT-X-TARGETDURATION:1\n#EXTINF:1,\nhttp://127.0.0.1:9/private.ts\n#EXT-X-ENDLIST\n");
        Path audio = temporary.resolve("audio.mp4");
        new LocalMediaProcess().run(List.of(FFMPEG, "-v", "error", "-f", "lavfi", "-i", "sine=duration=1",
                "-c:a", "aac", audio.toString()), MediaFixtures.budget(), InputStream::readAllBytes);
        for (Path input : List.of(invalid, playlist, audio)) {
            assertEquals(FailureCode.INVALID_MEDIA, assertThrows(MediaFailure.class,
                    () -> gateway(100000, 100000, 600, new LocalMediaProcess()).extract(input, () -> true)).code());
        }
        assertClean();
    }
    @Test void stopsRealFfmpegOnDeadlineAndLeaseLossWithoutLeavingPartials() throws Exception {
        Path input = video("mp4", "mpeg4", "3", "5");
        var alive = new AtomicBoolean(true);
        MediaProcess slow = (command, budget, reader) -> {
            var adjusted = new ArrayList<>(command);
            if (command.getFirst().equals(FFMPEG)) adjusted.add(adjusted.indexOf("-i"), "-re");
            new LocalMediaProcess().run(adjusted, budget, reader);
        };
        assertEquals(FailureCode.PROCESSING_TIMEOUT, assertThrows(MediaFailure.class,
                () -> gateway(1000000, 1000000, 1, slow).extract(input, alive::get)).code());
        assertClean();
        MediaProcess lose = (command, budget, reader) -> new LocalMediaProcess().run(command, budget, stream -> {
            if (command.getFirst().equals(FFMPEG)) alive.set(false);
            reader.read(stream);
        });
        assertThrows(IllegalStateException.class, () -> gateway(1000000, 1000000, 600, lose).extract(input, alive::get));
        assertClean();
        assertFalse(ProcessHandle.current().descendants().anyMatch(ProcessHandle::isAlive));
    }
    private void assertClean() throws IOException {
        try (var paths = Files.list(temporary.resolve("work"))) { assertEquals(0, paths.count()); }
    }
}
