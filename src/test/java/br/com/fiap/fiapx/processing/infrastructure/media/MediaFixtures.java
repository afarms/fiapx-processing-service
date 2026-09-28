package br.com.fiap.fiapx.processing.infrastructure.media;

import br.com.fiap.fiapx.processing.core.domain.ProcessingLimits;
import java.awt.image.BufferedImage;
import java.io.*;
import java.time.Duration;
import javax.imageio.ImageIO;

final class MediaFixtures {
    static final String PROBE = "frame|best_effort_timestamp_time=0.000000|duration_time=1.000000\n"
            + "stream|codec_type=video\nformat|format_name=mov,mp4,m4a,3gp,3g2,mj2|duration=1.000000\n";
    static ProcessingLimits limits(long png, long zip) {
        return new ProcessingLimits(100000000, 300, png, zip, 600, 3, 3221225472L, 120, 30);
    }
    static ExecutionBudget budget() { return new ExecutionBudget(Duration.ofSeconds(10), () -> true); }
    static byte[] png() throws IOException {
        var output = new ByteArrayOutputStream();
        ImageIO.write(new BufferedImage(16, 16, BufferedImage.TYPE_INT_RGB), "png", output);
        return output.toByteArray();
    }
}
