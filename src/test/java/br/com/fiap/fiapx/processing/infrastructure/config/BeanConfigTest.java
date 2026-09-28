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
    @Test void enabledMessagingWiresSchedulersWithFakeTransportOnly() {
        var client=mock(software.amazon.awssdk.services.sqs.SqsClient.class);
        org.mockito.Mockito.when(client.receiveMessage(org.mockito.ArgumentMatchers.any(software.amazon.awssdk.services.sqs.model.ReceiveMessageRequest.class)))
                .thenReturn(software.amazon.awssdk.services.sqs.model.ReceiveMessageResponse.builder().build());
        runner.withBean("testSqs",software.amazon.awssdk.services.sqs.SqsClient.class,()->client,definition->definition.setPrimary(true))
                .withPropertyValues("messaging.enabled=true","storage.enabled=true","storage.bucket=fiapx-media-test",
                        "storage.local-directory="+temporary.resolve("messaging"),
                        "messaging.work-queue-url=https://sqs.us-east-1.amazonaws.com/123456789012/work",
                        "messaging.result-queue-url=https://sqs.us-east-1.amazonaws.com/123456789012/events")
                .run(context->{
                    assertNull(context.getStartupFailure());
                    assertNotNull(context.getBean(br.com.fiap.fiapx.processing.infrastructure.messaging.WorkConsumer.class));
                    assertNotNull(context.getBean(br.com.fiap.fiapx.processing.infrastructure.messaging.ResultPublisher.class));
                });
    }

    @Test void composesMessagingResourcesWithoutStartingRemoteWork() throws Exception {
        var config=new BeanConfig();
        try (var credentials=software.amazon.awssdk.auth.credentials.DefaultCredentialsProvider.builder().build();
             var client=config.processingSqs(credentials,"us-east-1"); var scheduler=config.leaseScheduler()) {
            assertNotNull(client);
            var files=mock(br.com.fiap.fiapx.processing.infrastructure.storage.LocalProcessingArtifacts.class);
            var consumer=config.workConsumer(client,mock(JobGateway.class),mock(br.com.fiap.fiapx.processing.core.gateway.MediaGateway.class),
                    mock(br.com.fiap.fiapx.processing.core.usecase.StoreProcessingResult.class),br.com.fiap.fiapx.processing.Fixtures.limits(),
                    JsonMapper.builder().build(),scheduler,files,"https://sqs.us-east-1.amazonaws.com/123456789012/work","fiapx-media-test");
            consumer.close(); consumer.poll();
            assertNotNull(config.resultPublisher(mock(br.com.fiap.fiapx.processing.infrastructure.persistence.adapter.ProcessingOutbox.class),
                    client,"https://sqs.us-east-1.amazonaws.com/123456789012/events"));
            var cleanup=mock(br.com.fiap.fiapx.processing.core.usecase.CleanupProcessingArtifacts.class);
            var maintenance=config.artifactMaintenance(cleanup); maintenance.run();
            org.mockito.Mockito.verify(cleanup).run(20);
            org.mockito.Mockito.doThrow(new java.io.IOException("unavailable")).when(cleanup).run(20); maintenance.run();
            var tasks=config.taskScheduler(); tasks.initialize();
            try { assertEquals(3,tasks.getScheduledThreadPoolExecutor().getCorePoolSize()); } finally { tasks.shutdown(); }
        }
        runner.withPropertyValues("messaging.enabled=true", "storage.enabled=false").run(context->assertNotNull(context.getStartupFailure()));
    }

    @org.junit.jupiter.api.io.TempDir java.nio.file.Path temporary;
    private final ApplicationContextRunner runner = new ApplicationContextRunner()
            .withUserConfiguration(BeanConfig.class)
            .withBean(JsonMapper.class, () -> JsonMapper.builder().build())
            .withBean(SpringProcessingJobRepository.class, () -> mock(SpringProcessingJobRepository.class))
            .withBean(PlatformTransactionManager.class, () -> mock(PlatformTransactionManager.class))
            .withBean(org.springframework.jdbc.core.JdbcTemplate.class, () -> mock(org.springframework.jdbc.core.JdbcTemplate.class))
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
