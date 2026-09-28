package br.com.fiap.fiapx.processing.infrastructure.media;

import br.com.fiap.fiapx.processing.core.domain.*;
import java.io.*;
import java.math.BigDecimal;
import java.math.MathContext;
import java.nio.charset.StandardCharsets;
import java.util.*;

/** Reads decoded frame timestamps, including when container duration is missing or misleading. */
final class ProbeReport {
    private final BigDecimal maximum;
    private BigDecimal first, end, previous, lastDuration, interval, framePeriod;
    private boolean video, format;
    ProbeReport(long maximumSeconds) { maximum = BigDecimal.valueOf(maximumSeconds); }

    void read(InputStream stream) throws IOException {
        var reader = new InputStreamReader(stream, StandardCharsets.UTF_8);
        var line = new StringBuilder();
        for (int c; (c = reader.read()) != -1;) {
            if (c == '\n') { accept(line.toString()); line.setLength(0); }
            else { if (line.length() >= 4096) invalid(); line.append((char)c); }
        }
        if (!line.isEmpty()) accept(line.toString());
    }

    private void accept(String line) {
        String[] parts = line.trim().split("\\|");
        Map<String, String> fields = new HashMap<>();
        for (int i = 1; i < parts.length; i++) {
            int separator = parts[i].indexOf('=');
            if (separator > 0) fields.put(parts[i].substring(0, separator), parts[i].substring(separator + 1));
        }
        switch (parts[0]) {
            case "frame" -> {
                BigDecimal timestamp = number(fields.get("best_effort_timestamp_time"));
                BigDecimal duration = number(fields.get("duration_time"));
                if (timestamp == null || (duration != null && duration.signum() < 0)) invalid();
                if (first == null) first = timestamp;
                if (previous != null) {
                    interval = timestamp.subtract(previous);
                    if (interval.signum() <= 0) invalid();
                }
                previous = timestamp;
                lastDuration = duration != null && duration.signum() > 0 ? duration : null;
                BigDecimal frameEnd = timestamp.add(lastDuration == null ? BigDecimal.ZERO : lastDuration);
                end = end == null ? frameEnd : end.max(frameEnd);
                check(end.subtract(first));
            }
            case "stream" -> {
                video |= "video".equals(fields.get("codec_type"));
                String rate = fields.getOrDefault("avg_frame_rate", "0/0");
                String[] rational = rate.split("/");
                if (rational.length == 2) {
                    BigDecimal numerator = number(rational[0]), denominator = number(rational[1]);
                    if (numerator != null && denominator != null && numerator.signum() > 0 && denominator.signum() > 0)
                        framePeriod = denominator.divide(numerator, MathContext.DECIMAL128);
                }
            }
            case "format" -> {
                format = Arrays.stream(fields.getOrDefault("format_name", "").split(","))
                        .anyMatch(Set.of("mov", "avi", "matroska", "webm", "asf", "flv")::contains);
                BigDecimal declared = number(fields.get("duration"));
                if (declared != null) check(declared);
            }
            default -> { /* side-data sections are not duration evidence */ }
        }
    }

    void validate() {
        if (!video || !format || first == null || end == null) invalid();
        // ASF/FLV often omit packet durations. Use the observed last interval; a single
        // decoded frame requires an available stream frame period, otherwise fail closed.
        BigDecimal tail = lastDuration != null ? lastDuration : interval != null ? interval : framePeriod;
        if (tail == null) invalid();
        check(end.max(previous.add(tail)).subtract(first));
    }
    private void check(BigDecimal seconds) {
        if (seconds.compareTo(maximum) > 0) throw new MediaFailure(FailureCode.DURATION_EXCEEDED);
    }
    private static BigDecimal number(String value) {
        if (value == null || value.equals("N/A")) return null;
        if (!value.matches("-?\\d{1,12}(\\.\\d{1,9})?")) invalid();
        return new BigDecimal(value);
    }
    private static void invalid() { throw new MediaFailure(FailureCode.INVALID_MEDIA); }
}
