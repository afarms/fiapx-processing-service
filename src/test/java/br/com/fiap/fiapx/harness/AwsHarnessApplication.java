package br.com.fiap.fiapx.harness;

import br.com.fiap.fiapx.processing.infrastructure.ProcessingApplication;
import java.nio.file.Path;
import java.time.Duration;
import java.util.Objects;
import org.springframework.beans.factory.config.BeanPostProcessor;
import org.springframework.boot.builder.SpringApplicationBuilder;

/** Explicit test-classpath entrypoint; production component scanning cannot discover this package. */
public final class AwsHarnessApplication {
    public static void main(String[] args) {
        if (!"true".equals(System.getenv("PROCESSING_AWS_HARNESS_APPROVED")))
            throw new IllegalStateException("Review the run manifest before enabling the AWS harness");
        var controls = new FaultControl(Path.of(Objects.requireNonNull(System.getenv("HARNESS_CONTROL_DIRECTORY"))),
                System.getenv("HARNESS_WORKER"), Duration.ofMinutes(10));
        var adapters = new HarnessAdapters(controls);
        new SpringApplicationBuilder(ProcessingApplication.class).initializers(context ->
                context.getBeanFactory().addBeanPostProcessor(new BeanPostProcessor() {
                    @Override public Object postProcessAfterInitialization(Object bean, String name) { return adapters.wrap(bean); }
                })).run(args);
    }
}
