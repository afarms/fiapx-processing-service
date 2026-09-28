package br.com.fiap.fiapx.processing.infrastructure.config;

import static org.junit.jupiter.api.Assertions.*;
import br.com.fiap.fiapx.processing.core.domain.ProcessingLimits;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.boot.env.YamlPropertySourceLoader;
import org.springframework.core.io.ClassPathResource;
import br.com.fiap.fiapx.processing.core.gateway.JobGateway;
import br.com.fiap.fiapx.processing.infrastructure.persistence.repository.SpringProcessingJobRepository;
import org.springframework.transaction.PlatformTransactionManager;
import tools.jackson.databind.json.JsonMapper;
import static org.mockito.Mockito.mock;

class BeanConfigTest {
    @org.junit.jupiter.api.io.TempDir java.nio.file.Path temporary;
    private final ApplicationContextRunner runner = new ApplicationContextRunner()
            .withUserConfiguration(BeanConfig.class)
            .withBean(JsonMapper.class, () -> JsonMapper.builder().build())
            .withBean(SpringProcessingJobRepository.class, () -> mock(SpringProcessingJobRepository.class))
            .withBean(PlatformTransactionManager.class, () -> mock(PlatformTransactionManager.class))
            .withInitializer(context -> {
                try {
                    var sources = new YamlPropertySourceLoader().load("application", new ClassPathResource("application.yml"));
                    sources.forEach(source -> context.getEnvironment().getPropertySources().addLast(source));
                } catch (java.io.IOException failure) {
                    throw new java.io.UncheckedIOException(failure);
                }
            });

    @Test
    void composesOptionalStorageWithoutCallingAwsAndReleasesVolumeOnShutdown() throws Exception {
        for (String profile : new String[]{"", "local-test-profile"}) {
            var root=temporary.resolve(profile.isEmpty()?"default":"profile");
            runner.withPropertyValues("storage.enabled=true", "storage.bucket=fiapx-media-test", "storage.local-directory="+root,
                    "storage.aws-profile="+profile).run(context -> {
                assertNull(context.getStartupFailure());
                assertNotNull(context.getBean(br.com.fiap.fiapx.processing.core.usecase.StoreProcessingResult.class));
                assertNotNull(context.getBean(br.com.fiap.fiapx.processing.core.usecase.CleanupProcessingArtifacts.class));
                assertNotNull(context.getBean(software.amazon.awssdk.services.s3.S3Client.class));
            });
            try (var reopened=new br.com.fiap.fiapx.processing.infrastructure.storage.LocalProcessingArtifacts(root,1)) {
                assertNotNull(reopened);
            }
        }
    }

    @Test
    void startsCompositionWithRealApplicationDefaultsWithoutAwsCredentials() {
        runner.run(context -> {
            assertNull(context.getStartupFailure());
            assertNotNull(context.getBean(JobGateway.class));
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
