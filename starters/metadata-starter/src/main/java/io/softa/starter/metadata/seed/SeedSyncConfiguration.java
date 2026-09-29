package io.softa.starter.metadata.seed;

import org.apache.pulsar.client.api.DeadLetterPolicy;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * Caps the redelivery of a tenant seed sync message. Only a task whose instance stopped mid-run is
 * redelivered at all (a failure is recorded, not thrown), so a few attempts are enough; after them the
 * message lands on the subscription's dead-letter topic and the task keeps the state it reached.
 */
@Configuration(proxyBeanMethods = false)
public class SeedSyncConfiguration {

    @Bean("seedTenantSyncRetryPolicy")
    @ConditionalOnMissingBean(name = "seedTenantSyncRetryPolicy")
    public DeadLetterPolicy seedTenantSyncRetryPolicy() {
        return DeadLetterPolicy.builder().maxRedeliverCount(3).build();
    }
}
