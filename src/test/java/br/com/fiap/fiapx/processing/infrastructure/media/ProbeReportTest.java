package br.com.fiap.fiapx.processing.infrastructure.media;

import br.com.fiap.fiapx.processing.core.domain.*;
import java.io.*;
import java.nio.charset.StandardCharsets;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class ProbeReportTest {
    private static void inspect(String text) throws IOException {
        var report = new ProbeReport(300);
        report.read(new ByteArrayInputStream(text.getBytes(StandardCharsets.UTF_8))); report.validate();
    }
    @Test void validatesActualFramesWhenDurationIsMissingAndAcceptsExactBoundary() throws Exception {
        inspect(MediaFixtures.PROBE);
        inspect("frame|best_effort_timestamp_time=7|duration_time=300\nstream|codec_type=video\nformat|format_name=matroska|duration=N/A");
        inspect(MediaFixtures.PROBE.replace("duration=1.000000", "duration=N/A")
                + "side_data|side_data_type=test\nframe|best_effort_timestamp_time=299|duration_time=1\n");
        inspect(MediaFixtures.PROBE.replace("|duration=1.000000", ""));
    }
    @Test void rejectsForgedOrExcessiveDurationBasedOnDecodedTimeline() {
        for (String probe : new String[]{MediaFixtures.PROBE.replace("duration=1.000000", "duration=300.001"),
                MediaFixtures.PROBE + "frame|best_effort_timestamp_time=299|duration_time=1.001\n"}) {
            assertEquals(FailureCode.DURATION_EXCEEDED, assertThrows(MediaFailure.class, () -> inspect(probe)).code());
        }
    }
    @Test void infersMissingDurationFromDecodedIntervalsOrSingleFrameRate() throws Exception {
        String missing = MediaFixtures.PROBE.replace("duration_time=1.000000", "duration_time=N/A");
        inspect(missing + "frame|best_effort_timestamp_time=1|duration_time=0\n");
        inspect(missing.replace("codec_type=video", "codec_type=video|avg_frame_rate=1/1"));
        inspect(missing.replace("codec_type=video", "codec_type=video|avg_frame_rate=0/0")
                + "frame|best_effort_timestamp_time=1|duration_time=N/A\n");
        assertThrows(MediaFailure.class, () -> inspect(missing + "frame|best_effort_timestamp_time=300|duration_time=N/A\n"));
        assertThrows(MediaFailure.class, () -> inspect(missing + "frame|best_effort_timestamp_time=0|duration_time=N/A\n"));
        assertThrows(MediaFailure.class, () -> inspect(MediaFixtures.PROBE.replace("duration_time=1.000000", "duration_time=-1")));
    }
    @Test void rejectsUnverifiableFramesAndUnsupportedContent() {
        for (String probe : new String[]{"", "format|format_name=mov\nstream|codec_type=video\n",
                MediaFixtures.PROBE.replace("codec_type=video", "codec_type=audio"),
                MediaFixtures.PROBE.replace("mov,mp4,m4a,3gp,3g2,mj2", "hls"),
                MediaFixtures.PROBE.replace("format_name=mov,mp4,m4a,3gp,3g2,mj2", "irrelevant=x"),
                MediaFixtures.PROBE.replace("duration_time=1.000000", "duration_time=N/A"),
                MediaFixtures.PROBE.replace("duration_time=1.000000", "duration_time=0"),
                MediaFixtures.PROBE.replace("best_effort_timestamp_time=0.000000", "best_effort_timestamp_time=NaN"),
                MediaFixtures.PROBE.replace("best_effort_timestamp_time=0.000000", "missing=0"),
                MediaFixtures.PROBE + "frame|best_effort_timestamp_time=-1|duration_time=1\n",
                "x".repeat(4097), "stream|codec_type=video\n"}) {
            assertEquals(FailureCode.INVALID_MEDIA, assertThrows(MediaFailure.class, () -> inspect(probe)).code(), probe);
        }
    }
}
