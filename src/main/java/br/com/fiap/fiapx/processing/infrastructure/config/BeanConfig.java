package br.com.fiap.fiapx.processing.infrastructure.config;

import br.com.fiap.fiapx.processing.core.domain.ProcessingLimits;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration(proxyBeanMethods = false)
public class BeanConfig {
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
