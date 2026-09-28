package br.com.fiap.fiapx.processing.infrastructure.config;

import br.com.fiap.fiapx.processing.core.domain.ProcessingLimits;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import br.com.fiap.fiapx.processing.core.gateway.JobGateway;
import br.com.fiap.fiapx.processing.infrastructure.persistence.adapter.ProcessingJobGatewayAdapter;
import br.com.fiap.fiapx.processing.infrastructure.persistence.mapper.*;
import br.com.fiap.fiapx.processing.infrastructure.persistence.repository.SpringProcessingJobRepository;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionTemplate;
import tools.jackson.databind.json.JsonMapper;
import br.com.fiap.fiapx.processing.core.gateway.MediaGateway;
import br.com.fiap.fiapx.processing.infrastructure.media.*;
import java.nio.file.Path;
import java.time.Duration;
import br.com.fiap.fiapx.processing.core.gateway.*;
import br.com.fiap.fiapx.processing.core.usecase.*;
import br.com.fiap.fiapx.processing.infrastructure.storage.*;
import br.com.fiap.fiapx.processing.infrastructure.persistence.adapter.ArtifactCleanupAdapter;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import software.amazon.awssdk.auth.credentials.*;
import software.amazon.awssdk.regions.Region;
import software.amazon.awssdk.services.s3.S3Client;
import software.amazon.awssdk.http.urlconnection.UrlConnectionHttpClient;

@Configuration(proxyBeanMethods = false)
public class BeanConfig {
    @Bean
    ArtifactCleanupGateway artifactCleanupGateway(SpringProcessingJobRepository repository,
            ProcessingJobMapper mapper, PlatformTransactionManager manager) {
        var tx = new TransactionTemplate(manager);
        tx.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW); tx.setTimeout(10);
        return new ArtifactCleanupAdapter(repository, mapper, tx);
    }

    @Bean
    @ConditionalOnProperty(name = "storage.enabled", havingValue = "true")
    AwsCredentialsProvider storageCredentials(@Value("${storage.aws-profile:}") String profile) {
        return profile.isBlank() ? DefaultCredentialsProvider.builder().build() : ProfileCredentialsProvider.create(profile);
    }

    @Bean
    @ConditionalOnProperty(name = "storage.enabled", havingValue = "true")
    S3Client storageS3(AwsCredentialsProvider credentials, @Value("${storage.region}") String region) {
        return S3Client.builder().region(Region.of(region)).credentialsProvider(credentials)
                .httpClientBuilder(UrlConnectionHttpClient.builder().connectionTimeout(Duration.ofSeconds(5)).socketTimeout(Duration.ofSeconds(10)))
                .overrideConfiguration(c -> c.apiCallTimeout(Duration.ofSeconds(120)).apiCallAttemptTimeout(Duration.ofSeconds(60))).build();
    }

    @Bean
    @ConditionalOnProperty(name = "storage.enabled", havingValue = "true")
    LocalProcessingArtifacts localArtifacts(@Value("${storage.local-directory}") Path root, ProcessingLimits limits) throws java.io.IOException {
        return new LocalProcessingArtifacts(root, limits.diskReserveBytes());
    }

    @Bean
    @ConditionalOnProperty(name = "storage.enabled", havingValue = "true")
    ObjectStorageGateway objectStorage(S3Client client, @Value("${storage.bucket}") String bucket, ProcessingLimits limits) {
        return new S3ProcessingStorage(client, bucket, limits.maxZipBytes());
    }

    @Bean
    @ConditionalOnProperty(name = "storage.enabled", havingValue = "true")
    StoreProcessingResult storeProcessingResult(JobGateway jobs, ObjectStorageGateway objects, LocalProcessingArtifacts files) {
        return new StoreProcessingResult(jobs, objects, files);
    }

    @Bean
    @ConditionalOnProperty(name = "storage.enabled", havingValue = "true")
    CleanupProcessingArtifacts cleanupProcessingArtifacts(ArtifactCleanupGateway eligibility, ObjectStorageGateway objects, LocalProcessingArtifacts files) {
        return new CleanupProcessingArtifacts(eligibility, objects, files);
    }

    @Bean
    MediaGateway mediaGateway(ProcessingLimits limits,
            @Value("${processing.temp-directory}") String directory,
            @Value("${processing.ffmpeg}") String ffmpeg,
            @Value("${processing.ffprobe}") String ffprobe) {
        return new FfmpegMediaGateway(limits, Path.of(directory), ffmpeg, ffprobe, new LocalMediaProcess());
    }

    @Bean
    ProcessingJobMapper processingJobMapper(JsonMapper json) { return new ProcessingJobMapper(json); }

    @Bean
    ProcessingEventMapper processingEventMapper(JsonMapper json) { return new ProcessingEventMapper(json); }

    @Bean
    JobGateway jobGateway(SpringProcessingJobRepository repository, ProcessingJobMapper mapper,
            ProcessingEventMapper events, PlatformTransactionManager manager, ProcessingLimits limits) {
        var tx = new TransactionTemplate(manager);
        tx.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
        tx.setTimeout(10);
        return new ProcessingJobGatewayAdapter(repository, mapper, events, tx, limits);
    }

    @Bean
    ProcessingLimits processingLimits(
            @Value("${processing.max-input-bytes}") long input,
            @Value("${processing.max-duration-seconds}") long duration,
            @Value("${processing.max-extracted-bytes}") long extracted,
            @Value("${processing.max-zip-bytes}") long zip,
            @Value("${processing.timeout-seconds}") long timeout,
            @Value("${processing.max-attempts}") long attempts,
            @Value("${processing.disk-reserve-bytes}") long disk,
            @Value("${processing.lease-seconds}") long lease,
            @Value("${processing.heartbeat-seconds}") long heartbeat) {
        return new ProcessingLimits(input, duration, extracted, zip, timeout, attempts, disk, lease, heartbeat);
    }
}
