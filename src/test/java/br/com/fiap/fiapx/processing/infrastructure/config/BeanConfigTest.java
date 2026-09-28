package br.com.fiap.fiapx.processing.infrastructure.config;

import static org.junit.jupiter.api.Assertions.*;
import br.com.fiap.fiapx.processing.core.domain.ProcessingLimits;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.boot.env.YamlPropertySourceLoader;
import org.springframework.core.io.ClassPathResource;

class BeanConfigTest {
    private final ApplicationContextRunner runner = new ApplicationContextRunner()
            .withUserConfiguration(BeanConfig.class)
            .withInitializer(context -> {
                try {
                    var sources = new YamlPropertySourceLoader().load("application", new ClassPathResource("application.yml"));
                    sources.forEach(source -> context.getEnvironment().getPropertySources().addLast(source));
                } catch (java.io.IOException failure) {
                    throw new java.io.UncheckedIOException(failure);
                }
            });

    @Test
    void startsCompositionWithRealApplicationDefaultsWithoutAwsCredentials() {
        runner.run(context -> {
            assertNull(context.getStartupFailure());
            var limits = context.getBean(ProcessingLimits.class);
            assertEquals(3, limits.maxAttempts());
            assertEquals(100_000_000, limits.maxInputBytes());
            assertEquals(300, limits.maxDurationSeconds());
            assertEquals(600, limits.timeoutSeconds());
            assertEquals(1_073_741_824, limits.maxExtractedBytes());
            assertEquals(1_073_741_824, limits.maxZipBytes());
            assertEquals(3_221_225_472L, limits.diskReserveBytes());
            assertEquals(120, limits.leaseSeconds());
            assertEquals(30, limits.heartbeatSeconds());
        });
    }

    @Test
    void bindsExternalTuningAndRejectsUnsafeDiskConfiguration() {
        runner.withPropertyValues("PROCESSING_TIMEOUT_SECONDS=900").run(context -> {
            assertNull(context.getStartupFailure());
            assertEquals(900, context.getBean(ProcessingLimits.class).timeoutSeconds());
        });
        runner.withPropertyValues("PROCESSING_DISK_RESERVE_BYTES=1000").run(context -> {
            assertNotNull(context.getStartupFailure());
            assertTrue(context.getStartupFailure().getMessage().contains("processingLimits"));
        });
    }
}
