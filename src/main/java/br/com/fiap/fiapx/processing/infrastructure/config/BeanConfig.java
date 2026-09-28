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

@Configuration(proxyBeanMethods = false)
public class BeanConfig {
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
