package io.softa.starter.flow.message;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.function.Supplier;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

import io.softa.framework.base.context.Context;
import io.softa.framework.base.context.ContextHolder;
import io.softa.starter.cron.message.dto.CronTaskMessage;
import io.softa.starter.flow.design.trigger.ApiTrigger;
import io.softa.starter.flow.design.trigger.CronTrigger;
import io.softa.starter.flow.design.trigger.TriggerSource;
import io.softa.starter.flow.enums.FlowScenario;
import io.softa.starter.flow.runtime.api.FlowStartRequest;
import io.softa.starter.flow.runtime.bundle.CompiledFlowDefinition;
import io.softa.starter.flow.runtime.bundle.FlowBundleRegistry;
import io.softa.starter.flow.runtime.bundle.OrmFlowBundleRegistry;
import io.softa.starter.flow.runtime.bundle.OrmFlowBundleRegistryTest.StubBundleService;
import io.softa.starter.flow.runtime.engine.FlowRuntimeEngine;
import io.softa.starter.flow.runtime.state.FlowExecutionState;
import io.softa.starter.flow.runtime.trigger.DefaultFlowTriggerRegistry;
import io.softa.starter.flow.runtime.trigger.FlowAutomationService;
import io.softa.starter.flow.runtime.trigger.FlowTriggerEvent;
import io.softa.starter.flow.service.impl.FlowPublishServiceImpl;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * A cron message starts the one flow revision its job schedules, in the tenant it carries, and nothing else:
 * the other jobs on the shared cron topic — maintenance crons, other modules' rows — start no flow at all.
 */
class CronTaskFlowConsumerTest {

    private static final long TENANT_A = 7L;
    private static final long TENANT_B = 9L;
    private static final long SHARED = FlowBundleRegistry.SHARED_TENANT_ID;
    private static final LocalDateTime TRIGGER_TIME = LocalDateTime.of(2026, 10, 28, 9, 0);

    private StubBundleService bundleService;
    private OrmFlowBundleRegistry registry;
    private FlowAutomationService automationService;
    private CronTaskFlowConsumer consumer;
    /** Each flow start, with the tenant it ran in */
    private final List<Start> starts = new ArrayList<>();

    @BeforeEach
    void setUp() {
        bundleService = new StubBundleService();
        registry = new OrmFlowBundleRegistry(bundleService, 60);
        FlowRuntimeEngine engine = mock(FlowRuntimeEngine.class);
        when(engine.start(any())).thenAnswer(invocation -> {
            starts.add(new Start(invocation.getArgument(0), ContextHolder.getContext().getTenantId()));
            return new FlowExecutionState();
        });
        automationService = new FlowAutomationService(new DefaultFlowTriggerRegistry(registry), engine);
        consumer = new CronTaskFlowConsumer();
        ReflectionTestUtils.setField(consumer, "automationService", automationService);
    }

    @Test
    void startsTheRevisionItsJobSchedulesInTheJobsTenant() {
        CompiledFlowDefinition report = publish(TENANT_A, "report", "daily-report", new CronTrigger("0 0 9 ? * MON-FRI"));

        consumer.onMessage(flowJob(report.getBundleId(), TENANT_A));

        assertEquals(1, starts.size());
        Start start = starts.getFirst();
        assertEquals(report.getBundleId(), start.request().getBundleId());
        assertEquals(TENANT_A, start.tenantId());
        assertEquals(TRIGGER_TIME, start.request().getVariables().get(CronTaskFlowConsumer.PARAM_TRIGGER_TIME));
        assertEquals(FlowAutomationService.CRON_TRIGGER_TYPE, start.request().getVariables().get("_triggerType"));
    }

    /**
     * Before, every cron message became a Cron trigger event that every published Cron flow matched.
     */
    @Test
    void unrelatedCronTicksStartNothing() {
        CompiledFlowDefinition report = publish(TENANT_A, "report", "daily-report", new CronTrigger("0 0 9 ? * MON-FRI"));
        publish(SHARED, "cleanup", "shared-cleanup", new CronTrigger("0 0 1 * * ?"));

        // a maintenance row ticking across tenants
        consumer.onMessage(rowTick(6L, "flow_timer_sweep", crossTenant()));
        // another module's row, fanned out per tenant
        consumer.onMessage(rowTick(13L, "DynamicRoleSync", tenant(TENANT_A)));
        // a row that happens to be named like a flow's job is still a row
        consumer.onMessage(rowTick(21L, CronFlowJobProvider.jobName(report.getBundleId()), tenant(TENANT_A)));

        assertTrue(starts.isEmpty());
    }

    @Test
    void jobOfAReplacedRevisionStartsNothing() {
        CompiledFlowDefinition r1 = publish(TENANT_A, "report", "daily-report", new CronTrigger("0 0 9 ? * MON-FRI"));
        CompiledFlowDefinition r2 = publish(TENANT_A, "report", "daily-report", new CronTrigger("0 0 18 ? * MON-FRI"));

        consumer.onMessage(flowJob(r1.getBundleId(), TENANT_A));
        consumer.onMessage(flowJob(r2.getBundleId(), TENANT_A));
        assertEquals(List.of(r2.getBundleId()), startedBundles());

        // a rollback makes the first revision's schedule the flow's again
        FlowPublishServiceImpl publishService = new FlowPublishServiceImpl(null, registry);
        ReflectionTestUtils.setField(publishService, "bundleService", bundleService);
        inTenant(TENANT_A, () -> publishService.activateBundle(r1.getBundleId()));
        starts.clear();
        consumer.onMessage(flowJob(r1.getBundleId(), TENANT_A));
        consumer.onMessage(flowJob(r2.getBundleId(), TENANT_A));
        assertEquals(List.of(r1.getBundleId()), startedBundles());
    }

    @Test
    void flowWhoseTriggerIsNoLongerCronStartsNothing() {
        CompiledFlowDefinition r1 = publish(TENANT_A, "report", "daily-report", new CronTrigger("0 0 9 ? * MON-FRI"));
        CompiledFlowDefinition r2 = publish(TENANT_A, "report", "daily-report", new ApiTrigger(null));

        consumer.onMessage(flowJob(r1.getBundleId(), TENANT_A));
        consumer.onMessage(flowJob(r2.getBundleId(), TENANT_A));

        assertTrue(starts.isEmpty());
    }

    @Test
    void flowOfOneTenantNeverStartsInAnother() {
        CompiledFlowDefinition report = publish(TENANT_A, "report", "daily-report", new CronTrigger("0 0 9 ? * MON-FRI"));

        consumer.onMessage(flowJob(report.getBundleId(), TENANT_B));

        assertTrue(starts.isEmpty());
    }

    @Test
    void sharedFlowStartsInEveryTenantItsJobRunsIn() {
        CompiledFlowDefinition cleanup = publish(SHARED, "cleanup", "shared-cleanup", new CronTrigger("0 0 1 * * ?"));

        consumer.onMessage(flowJob(cleanup.getBundleId(), TENANT_A));
        consumer.onMessage(flowJob(cleanup.getBundleId(), TENANT_B));

        assertEquals(List.of(TENANT_A, TENANT_B), starts.stream().map(Start::tenantId).toList());
        assertEquals(List.of(cleanup.getBundleId(), cleanup.getBundleId()), startedBundles());
    }

    @Test
    void cronFlowsAreNotStartedByATriggerEvent() {
        publish(TENANT_A, "report", "daily-report", new CronTrigger("0 0 9 ? * MON-FRI"));
        FlowTriggerEvent cronEvent = FlowTriggerEvent.builder().type("Cron").sourceModel("cron:6").build();

        inTenant(TENANT_A, () -> {
            automationService.fireAsyncOnly(cronEvent);
            return null;
        });
        assertTrue(starts.isEmpty());
    }

    // ==================== Helpers ====================

    private CompiledFlowDefinition publish(long tenantId, String flowId, String flowName, TriggerSource trigger) {
        CompiledFlowDefinition definition = CompiledFlowDefinition.builder()
                .flowName(flowName)
                .scenario(FlowScenario.AUTOMATION)
                .compiledAt(LocalDateTime.now())
                .trigger(trigger)
                .build();
        return inTenant(tenantId, () -> registry.register(definition, null, flowId));
    }

    private List<Long> startedBundles() {
        return starts.stream().map(start -> start.request().getBundleId()).toList();
    }

    /** What the cron scheduler sends for a flow's job: no cron id, the revision in the name, the job's tenant. */
    private static CronTaskMessage flowJob(Long bundleId, long tenantId) {
        CronTaskMessage message = new CronTaskMessage();
        message.setCronName(CronFlowJobProvider.jobName(bundleId));
        message.setTriggerTime(TRIGGER_TIME);
        message.setContext(tenant(tenantId));
        return message;
    }

    private static CronTaskMessage rowTick(Long cronId, String cronName, Context context) {
        CronTaskMessage message = new CronTaskMessage();
        message.setCronId(cronId);
        message.setCronName(cronName);
        message.setTriggerTime(TRIGGER_TIME);
        message.setContext(context);
        return message;
    }

    private static Context tenant(long tenantId) {
        Context context = new Context();
        context.setTenantId(tenantId);
        return context;
    }

    private static Context crossTenant() {
        Context context = new Context();
        context.setCrossTenant(true);
        return context;
    }

    private static <T> T inTenant(long tenantId, Supplier<T> action) {
        return ContextHolder.callWith(tenant(tenantId), action::get);
    }

    private record Start(FlowStartRequest request, Long tenantId) {}
}
