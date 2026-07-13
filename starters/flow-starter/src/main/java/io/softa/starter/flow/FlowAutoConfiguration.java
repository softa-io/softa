package io.softa.starter.flow;

import org.apache.pulsar.client.api.DeadLetterPolicy;
import org.springframework.boot.autoconfigure.condition.ConditionalOnClass;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.ComponentScan;

import io.softa.framework.orm.service.ModelService;
import io.softa.starter.flow.runtime.spi.ApprovalNotificationService;
import io.softa.starter.flow.runtime.spi.OrganizationService;
import io.softa.starter.flow.runtime.spi.impl.DefaultApprovalNotificationService;
import io.softa.starter.flow.runtime.spi.impl.MetadataOrganizationService;
import io.softa.starter.flow.runtime.spi.impl.OrganizationServiceProperties;

/**
 * Auto configuration for the flow starter.
 */
@ComponentScan
@EnableConfigurationProperties(OrganizationServiceProperties.class)
public class FlowAutoConfiguration {

    /**
     * Default dead-letter policy shared by all flow-starter Pulsar consumers.
     * Redelivery is capped at 3 attempts; messages that keep failing after that
     * are routed to the broker's default DLQ topic ({@code <topic>-<subscription>-DLQ}).
     */
    @Bean("flowDeadLetterPolicy")
    @ConditionalOnClass(DeadLetterPolicy.class)
    @ConditionalOnMissingBean(name = "flowDeadLetterPolicy")
    public DeadLetterPolicy flowDeadLetterPolicy() {
        return DeadLetterPolicy.builder()
                .maxRedeliverCount(3)
                .build();
    }

    /**
     * Default organization lookup for dynamic approver resolution, backed by the metadata
     * {@link ModelService}. Declared as an auto-configuration {@code @Bean} (not a component-scanned
     * {@code @Component}) so {@link ConditionalOnMissingBean} reliably yields to an
     * application-provided {@link OrganizationService} regardless of bean scan ordering — the
     * previous {@code @Component} + {@code @ConditionalOnMissingBean} combination could silently
     * fail to register, leaving dynamic approver sources resolving to an empty list.
     */
    @Bean
    @ConditionalOnMissingBean(OrganizationService.class)
    public MetadataOrganizationService flowMetadataOrganizationService(
            ModelService<?> modelService, OrganizationServiceProperties properties) {
        return new MetadataOrganizationService(modelService, properties);
    }

    /**
     * Default no-op notification service (logs each event). Yields to an application-provided
     * {@link ApprovalNotificationService} via {@link ConditionalOnMissingBean}.
     */
    @Bean
    @ConditionalOnMissingBean(ApprovalNotificationService.class)
    public DefaultApprovalNotificationService flowDefaultApprovalNotificationService() {
        return new DefaultApprovalNotificationService();
    }
}

