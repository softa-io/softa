package io.softa.starter.flow.message;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Set;
import java.util.function.Supplier;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import io.softa.framework.base.config.SystemConfig;
import io.softa.framework.base.context.Context;
import io.softa.framework.base.context.ContextHolder;
import io.softa.starter.cron.job.CronJob;
import io.softa.starter.cron.message.dto.CronTaskMessage;
import io.softa.starter.flow.design.trigger.ApiTrigger;
import io.softa.starter.flow.design.trigger.CronTrigger;
import io.softa.starter.flow.design.trigger.TriggerSource;
import io.softa.starter.flow.enums.FlowScenario;
import io.softa.starter.flow.runtime.bundle.CompiledFlowDefinition;
import io.softa.starter.flow.runtime.bundle.FlowBundleRegistry;
import io.softa.starter.flow.runtime.bundle.OrmFlowBundleRegistry;
import io.softa.starter.flow.runtime.bundle.OrmFlowBundleRegistryTest.StubBundleService;

import static org.junit.jupiter.api.Assertions.*;

/**
 * The cron jobs of the flows are their active Cron-triggered revisions, read across tenants, each on its own expression.
 */
class CronFlowJobProviderTest {

    private static final long TENANT_A = 7L;
    private static final long TENANT_B = 9L;

    private final StubBundleService bundleService = new StubBundleService();
    private final OrmFlowBundleRegistry registry = new OrmFlowBundleRegistry(bundleService);
    private final CronFlowJobProvider provider = new CronFlowJobProvider(bundleService);

    @BeforeEach
    void enableMultiTenancy() {
        multiTenancy(true);
    }

    @AfterEach
    void resetSystemConfig() {
        SystemConfig.env = null;
    }

    @Test
    void oneJobPerActiveCronRevisionOnItsOwnExpression() {
        publish(TENANT_A, "report", new CronTrigger("0 0 9 ? * MON-FRI"));
        CompiledFlowDefinition report = publish(TENANT_A, "report", new CronTrigger("0 0 18 ? * MON-FRI"));
        CompiledFlowDefinition digest = publish(TENANT_B, "digest", new CronTrigger("0 30 7 * * ?"));
        CompiledFlowDefinition cleanup = publish(FlowBundleRegistry.SHARED_TENANT_ID, "cleanup", new CronTrigger("0 0 1 * * ?"));
        publish(TENANT_B, "manual", new ApiTrigger(null));

        // Read on a thread with no context, as the scheduler does
        assertFalse(ContextHolder.existContext());
        assertEquals(Set.of(
                        CronJob.ofTenant(CronFlowJobProvider.jobName(report.getBundleId()), "0 0 18 ? * MON-FRI", TENANT_A),
                        CronJob.ofTenant(CronFlowJobProvider.jobName(digest.getBundleId()), "0 30 7 * * ?", TENANT_B),
                        // a platform-shared flow runs once in every active tenant
                        CronJob.perTenant(CronFlowJobProvider.jobName(cleanup.getBundleId()), "0 0 1 * * ?")),
                Set.copyOf(provider.listCronJobs()));
    }

    @Test
    void flowWhoseTriggerIsNoLongerCronHasNoJob() {
        publish(TENANT_A, "report", new CronTrigger("0 0 9 ? * MON-FRI"));
        publish(TENANT_A, "report", new ApiTrigger(null));

        assertTrue(provider.listCronJobs().isEmpty());
    }

    @Test
    void withoutTenantsEveryFlowRunsOnceWithNoTenant() {
        CompiledFlowDefinition report = publish(FlowBundleRegistry.SHARED_TENANT_ID, "report", new CronTrigger("0 0 9 ? * MON-FRI"));
        multiTenancy(false);

        assertEquals(List.of(CronJob.of(CronFlowJobProvider.jobName(report.getBundleId()), "0 0 9 ? * MON-FRI")),
                provider.listCronJobs());
    }

    @Test
    void messagesOfOtherJobsNameNoRevision() {
        assertEquals(42L, CronFlowJobProvider.bundleIdOf(message(null, "flow:42")));
        // a SysCron row, whatever its name
        assertNull(CronFlowJobProvider.bundleIdOf(message(5L, "flow:42")));
        assertNull(CronFlowJobProvider.bundleIdOf(message(6L, "flow_timer_sweep")));
        assertNull(CronFlowJobProvider.bundleIdOf(message(null, "flow:daily")));
        assertNull(CronFlowJobProvider.bundleIdOf(message(null, "mail-fetch")));
        assertNull(CronFlowJobProvider.bundleIdOf(message(null, null)));
    }

    private CompiledFlowDefinition publish(long tenantId, String flowId, TriggerSource trigger) {
        CompiledFlowDefinition definition = CompiledFlowDefinition.builder()
                .flowName(flowId)
                .scenario(FlowScenario.AUTOMATION)
                .compiledAt(LocalDateTime.now())
                .trigger(trigger)
                .build();
        return inTenant(tenantId, () -> registry.register(definition, null, flowId));
    }

    private static void multiTenancy(boolean enabled) {
        SystemConfig config = new SystemConfig();
        config.setEnableMultiTenancy(enabled);
        SystemConfig.env = config;
    }

    private static CronTaskMessage message(Long cronId, String cronName) {
        CronTaskMessage message = new CronTaskMessage();
        message.setCronId(cronId);
        message.setCronName(cronName);
        return message;
    }

    private static <T> T inTenant(long tenantId, Supplier<T> action) {
        Context context = new Context();
        context.setTenantId(tenantId);
        return ContextHolder.callWith(context, action::get);
    }
}
