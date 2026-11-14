package io.softa.starter.flow.message;

import java.time.LocalDateTime;
import java.time.ZonedDateTime;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.ScheduledThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.function.Supplier;

import com.cronutils.model.time.ExecutionTime;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

import io.softa.framework.base.config.SystemConfig;
import io.softa.framework.base.context.Context;
import io.softa.framework.base.context.ContextHolder;
import io.softa.framework.orm.compute.CronUtils;
import io.softa.framework.orm.jdbc.JdbcService;
import io.softa.framework.orm.service.TenantInfoService;
import io.softa.starter.cron.entity.SysCron;
import io.softa.starter.cron.enums.TenantJobMode;
import io.softa.starter.cron.message.CronTaskProducer;
import io.softa.starter.cron.message.dto.CronTaskMessage;
import io.softa.starter.cron.scheduler.CronScheduler;
import io.softa.starter.cron.scheduler.SchedulerPool;
import io.softa.starter.flow.design.trigger.CronTrigger;
import io.softa.starter.flow.enums.FlowScenario;
import io.softa.starter.flow.runtime.api.FlowStartRequest;
import io.softa.starter.flow.runtime.bundle.CompiledFlowDefinition;
import io.softa.starter.flow.runtime.bundle.OrmFlowBundleRegistry;
import io.softa.starter.flow.runtime.bundle.OrmFlowBundleRegistryTest.StubBundleService;
import io.softa.starter.flow.runtime.engine.FlowRuntimeEngine;
import io.softa.starter.flow.runtime.state.FlowExecutionState;
import io.softa.starter.flow.runtime.trigger.DefaultFlowTriggerRegistry;
import io.softa.starter.flow.runtime.trigger.FlowAutomationService;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * A Cron-triggered flow from publish to start, through cron-starter's real scheduler: the leader schedules the flow's active
 * revision on the flow's own expression, beside the {@code SysCron} rows, and the trigger its run sends starts that flow — while the
 * rows' triggers start none. The executor is real; every expression is yearly, so that no run fires on its own.
 */
class CronFlowScheduleTest {

    private static final long TENANT = 7L;
    private static final String FLOW_ID = "report";
    private static final String ROW_EXPRESSION = "0 0 2 1 1 ?";

    /** The job runs handed to the executor, in the order they were scheduled */
    private final List<ScheduledRun> scheduledRuns = new CopyOnWriteArrayList<>();
    /** The cron triggers the scheduler sent */
    private final List<CronTaskMessage> sent = new CopyOnWriteArrayList<>();
    /** The flow starts */
    private final List<Start> starts = new CopyOnWriteArrayList<>();
    private ScheduledThreadPoolExecutor executor;
    private OrmFlowBundleRegistry registry;
    private CronScheduler cronScheduler;
    private CronTaskFlowConsumer consumer;

    @BeforeEach
    void setUp() {
        SystemConfig config = new SystemConfig();
        config.setEnableMultiTenancy(true);
        SystemConfig.env = config;
        StubBundleService bundleService = new StubBundleService();
        registry = new OrmFlowBundleRegistry(bundleService);

        executor = new ScheduledThreadPoolExecutor(1) {
            @Override
            public ScheduledFuture<?> schedule(Runnable command, long delay, TimeUnit unit) {
                ScheduledFuture<?> scheduledTask = super.schedule(command, delay, unit);
                scheduledRuns.add(new ScheduledRun(command, scheduledTask, unit.toMillis(delay)));
                return scheduledTask;
            }
        };
        SchedulerPool schedulerPool = mock(SchedulerPool.class);
        when(schedulerPool.getScheduler()).thenReturn(executor);
        JdbcService<?> jdbcService = mock(JdbcService.class);
        when(jdbcService.selectMetaEntityList(eq(SysCron.class), any())).thenReturn(List.of(maintenanceRow()));
        CronTaskProducer cronTaskProducer = mock(CronTaskProducer.class);
        doAnswer(invocation -> sent.add(invocation.getArgument(0))).when(cronTaskProducer).sendCronTask(any());
        TenantInfoService tenantInfoService = mock(TenantInfoService.class);
        when(tenantInfoService.isTenantActive(anyLong())).thenReturn(true);

        cronScheduler = new CronScheduler();
        ReflectionTestUtils.setField(cronScheduler, "schedulerPool", schedulerPool);
        ReflectionTestUtils.setField(cronScheduler, "jdbcService", jdbcService);
        ReflectionTestUtils.setField(cronScheduler, "cronTaskProducer", cronTaskProducer);
        ReflectionTestUtils.setField(cronScheduler, "tenantInfoService", tenantInfoService);
        ReflectionTestUtils.setField(cronScheduler, "cronJobProviders", List.of(new CronFlowJobProvider(bundleService)));

        FlowRuntimeEngine engine = mock(FlowRuntimeEngine.class);
        when(engine.start(any())).thenAnswer(invocation -> {
            FlowStartRequest request = invocation.getArgument(0);
            starts.add(new Start(request.getBundleId(), ContextHolder.getContext().getTenantId()));
            return new FlowExecutionState();
        });
        consumer = new CronTaskFlowConsumer();
        ReflectionTestUtils.setField(consumer, "automationService",
                new FlowAutomationService(new DefaultFlowTriggerRegistry(registry), engine));
    }

    @AfterEach
    void tearDown() {
        executor.shutdownNow();
        SystemConfig.env = null;
    }

    @Test
    void cronFlowStartsOnItsOwnScheduleAndTheRowsStartNothing() {
        CompiledFlowDefinition report = publish("0 0 3 1 1 ?");

        cronScheduler.start();

        // The maintenance row and the flow, each due at the next execution of its own expression
        assertEquals(2, scheduledRuns.size());
        List<ScheduledRun> firstRuns = List.copyOf(scheduledRuns);
        assertDueAtNextExecutionOf(ROW_EXPRESSION, firstRuns.get(0));
        assertDueAtNextExecutionOf("0 0 3 1 1 ?", firstRuns.get(1));

        // Both fire, and their triggers reach the flow consumer
        firstRuns.forEach(run -> run.command().run());
        assertEquals(List.of("flow_timer_sweep", CronFlowJobProvider.jobName(report.getBundleId())),
                sent.stream().map(CronTaskMessage::getCronName).toList());
        sent.forEach(consumer::onMessage);

        assertEquals(List.of(new Start(report.getBundleId(), TENANT)), starts);
    }

    @Test
    void republishingMovesTheScheduleToTheNewRevision() {
        CompiledFlowDefinition r1 = publish("0 0 3 1 1 ?");
        cronScheduler.start();
        ScheduledRun runOfR1 = scheduledRuns.get(1);

        CompiledFlowDefinition r2 = publish("0 0 4 1 1 ?");
        ReflectionTestUtils.invokeMethod(cronScheduler, "refresh");

        assertTrue(runOfR1.future().isCancelled());
        assertEquals(3, scheduledRuns.size());
        ScheduledRun runOfR2 = scheduledRuns.get(2);
        assertDueAtNextExecutionOf("0 0 4 1 1 ?", runOfR2);

        // A run of the first revision that was already firing still sends, and starts nothing
        runOfR1.command().run();
        runOfR2.command().run();
        assertEquals(List.of(CronFlowJobProvider.jobName(r1.getBundleId()), CronFlowJobProvider.jobName(r2.getBundleId())),
                sent.stream().map(CronTaskMessage::getCronName).toList());
        sent.forEach(consumer::onMessage);

        assertEquals(List.of(new Start(r2.getBundleId(), TENANT)), starts);
    }

    private CompiledFlowDefinition publish(String cronExpression) {
        CompiledFlowDefinition definition = CompiledFlowDefinition.builder()
                .flowName("daily-report")
                .scenario(FlowScenario.AUTOMATION)
                .compiledAt(LocalDateTime.now())
                .trigger(new CronTrigger(cronExpression))
                .build();
        return inTenant(() -> registry.register(definition, null, FLOW_ID));
    }

    /** A flow maintenance row, ticking across tenants */
    private static SysCron maintenanceRow() {
        SysCron sysCron = new SysCron();
        sysCron.setId(6L);
        sysCron.setName("flow_timer_sweep");
        sysCron.setCronExpression(ROW_EXPRESSION);
        sysCron.setLimitExecution(false);
        sysCron.setTenantJobMode(TenantJobMode.CROSS_TENANT);
        sysCron.setActive(true);
        return sysCron;
    }

    private static void assertDueAtNextExecutionOf(String cronExpression, ScheduledRun run) {
        ZonedDateTime next = ExecutionTime.forCron(CronUtils.getCron("expected", cronExpression))
                .nextExecution(ZonedDateTime.now()).orElseThrow();
        long expectedDelayMs = next.toInstant().toEpochMilli() - System.currentTimeMillis();
        // Scheduled a moment ago, so the delay it got is at most a little longer than the one left now
        assertTrue(Math.abs(run.delayMs() - expectedDelayMs) < 5_000,
                () -> "due in " + run.delayMs() + "ms, while " + cronExpression + " next runs in " + expectedDelayMs + "ms");
    }

    private static <T> T inTenant(Supplier<T> action) {
        Context context = new Context();
        context.setTenantId(TENANT);
        return ContextHolder.callWith(context, action::get);
    }

    private record ScheduledRun(Runnable command, ScheduledFuture<?> future, long delayMs) {}

    /** A flow start: the revision started, and the tenant it ran in */
    private record Start(Long bundleId, Long tenantId) {}
}
