package io.softa.starter.flow;

import java.util.List;
import java.util.Objects;
import java.util.function.Supplier;
import org.apache.pulsar.client.api.DeadLetterPolicy;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnClass;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.ComponentScan;
import org.springframework.context.annotation.Configuration;
import org.springframework.transaction.PlatformTransactionManager;

import io.softa.framework.base.config.SystemConfig;
import io.softa.framework.orm.service.ModelService;
import io.softa.starter.flow.api.FlowClient;
import io.softa.starter.flow.event.SeedFlowPublisher;
import io.softa.starter.flow.runtime.bundle.FlowBundleRegistry;
import io.softa.starter.flow.runtime.descriptor.FlowNodeDescriptorRegistry;
import io.softa.starter.flow.runtime.engine.FlowRuntimeEngine;
import io.softa.starter.flow.runtime.engine.FormPermissionService;
import io.softa.starter.flow.runtime.engine.InProcessFlowClient;
import io.softa.starter.flow.runtime.spi.ApprovalNotificationService;
import io.softa.starter.flow.runtime.spi.ApproverInfo;
import io.softa.starter.flow.runtime.spi.OrganizationService;
import io.softa.starter.flow.runtime.spi.impl.CompositeApprovalNotificationService;
import io.softa.starter.flow.runtime.spi.impl.InboxApprovalNotificationService;
import io.softa.starter.flow.runtime.spi.impl.InboxReadMarker;
import io.softa.starter.flow.runtime.spi.impl.MailApprovalNotificationService;
import io.softa.starter.flow.runtime.spi.impl.FlowOrgService;
import io.softa.starter.flow.runtime.spi.impl.OrganizationServiceProperties;
import io.softa.starter.flow.service.FlowApprovalRecordQueryService;
import io.softa.starter.flow.service.FlowApprovalTaskQueryService;
import io.softa.starter.flow.service.FlowInboxService;
import io.softa.starter.flow.service.FlowInstanceService;
import io.softa.starter.flow.service.FlowLaunchService;
import io.softa.starter.flow.service.FlowPublishService;
import io.softa.starter.flow.service.support.FlowInstanceAccessGuard;
import io.softa.starter.message.inbox.service.InboxNotificationService;
import io.softa.starter.message.service.MessageService;

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
     * Default organization lookup for dynamic approver resolution — {@link FlowOrgService}, over the
     * HR conventions permission-starter reads. Declared as an auto-configuration {@code @Bean} (not a component-scanned
     * {@code @Component}) so {@link ConditionalOnMissingBean} reliably yields to an
     * application-provided {@link OrganizationService} regardless of bean scan ordering — the
     * previous {@code @Component} + {@code @ConditionalOnMissingBean} combination could silently
     * fail to register, leaving dynamic approver sources resolving to an empty list.
     */
    @Bean
    @ConditionalOnMissingBean(OrganizationService.class)
    public FlowOrgService flowOrgService(ModelService<?> modelService, OrganizationServiceProperties properties) {
        return new FlowOrgService(modelService, properties);
    }

    /**
     * Default in-process {@link FlowClient}. An auto-configuration {@code @Bean} for the same
     * reason as {@link #flowOrgService}: {@link ConditionalOnMissingBean} then
     * reliably yields to an application-provided {@link FlowClient}.
     */
    @Bean
    @ConditionalOnMissingBean(FlowClient.class)
    public InProcessFlowClient flowInProcessFlowClient(
            FlowRuntimeEngine engine, FlowLaunchService launch, FormPermissionService forms,
            FlowApprovalTaskQueryService taskQuery, FlowApprovalRecordQueryService recordQuery,
            FlowInboxService inbox, FlowInstanceService instances,
            FlowNodeDescriptorRegistry descriptors, FlowInstanceAccessGuard accessGuard) {
        return new InProcessFlowClient(engine, launch, forms, taskQuery, recordQuery, inbox,
                instances, descriptors, accessGuard);
    }

    /**
     * Default notification service: inbox messages always, and emails on the flows that send them, through
     * message-starter, written after the action commits. A failed approval also tells the holders of
     * {@code flow.notification.failure-role-codes} (default {@code TENANT_ADMIN}). Yields to an
     * application-provided {@link ApprovalNotificationService} via {@link ConditionalOnMissingBean}.
     */
    @Bean
    @ConditionalOnMissingBean(ApprovalNotificationService.class)
    public CompositeApprovalNotificationService flowApprovalNotificationService(
            ObjectProvider<MessageService> messageService, ModelService<?> modelService,
            PlatformTransactionManager transactionManager, FlowBundleRegistry bundleRegistry,
            ObjectProvider<SystemConfig> systemConfig, ObjectProvider<OrganizationService> organizationService,
            @Value("${flow.notification.failure-role-codes:TENANT_ADMIN}") List<String> failureRoleCodes) {
        Supplier<List<String>> administrators = () -> {
            OrganizationService organization = organizationService.getIfAvailable();
            return organization == null ? List.of() : organization.getUsersByRoleCodes(failureRoleCodes).stream()
                    .map(ApproverInfo::getUserId)
                    .filter(Objects::nonNull)
                    .map(String::valueOf)
                    .toList();
        };
        return new CompositeApprovalNotificationService(List.of(
                new InboxApprovalNotificationService(messageService, modelService, transactionManager, administrators),
                new MailApprovalNotificationService(messageService, modelService, transactionManager, bundleRegistry,
                        systemConfig, administrators)));
    }

    /** Marks an approver's inbox messages about an approval read once they act on it. */
    @Bean
    public InboxReadMarker flowInboxReadMarker(ObjectProvider<InboxNotificationService> inbox,
                                               PlatformTransactionManager transactionManager) {
        return new InboxReadMarker(inbox, transactionManager);
    }

    /**
     * Publishes the flow designs tenant seed files write, when the application uses seed-starter
     * (an optional dependency: the class is checked by name before anything loads it).
     */
    @Configuration(proxyBeanMethods = false)
    @ConditionalOnClass(name = "io.softa.starter.seed.event.SeedFileLoadedEvent")
    static class SeedFlowPublishing {

        @Bean
        SeedFlowPublisher flowSeedFlowPublisher(FlowPublishService publishService) {
            return new SeedFlowPublisher(publishService);
        }
    }
}

