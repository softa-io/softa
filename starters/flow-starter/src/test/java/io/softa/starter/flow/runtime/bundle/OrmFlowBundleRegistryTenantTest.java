package io.softa.starter.flow.runtime.bundle;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;
import java.util.function.Supplier;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

import io.softa.framework.base.context.Context;
import io.softa.framework.base.context.ContextHolder;
import io.softa.framework.orm.changelog.message.dto.ChangeLog;
import io.softa.framework.orm.enums.AccessType;
import io.softa.starter.flow.controller.FlowBundleController;
import io.softa.starter.flow.design.trigger.ChangeEvent;
import io.softa.starter.flow.design.trigger.EntityChangeTrigger;
import io.softa.starter.flow.dto.FlowBundleSummaryView;
import io.softa.starter.flow.enums.FlowNodeType;
import io.softa.starter.flow.enums.FlowScenario;
import io.softa.starter.flow.message.ChangeLogTriggerMapper;
import io.softa.starter.flow.runtime.bundle.OrmFlowBundleRegistryTest.StubBundleService;
import io.softa.starter.flow.runtime.trigger.DefaultFlowTriggerRegistry;
import io.softa.starter.flow.runtime.trigger.FlowTriggerEvent;
import io.softa.starter.flow.service.impl.FlowPublishServiceImpl;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Tenant isolation of {@link OrmFlowBundleRegistry}'s caches, against a stub service that
 * filters by the context tenant the way the ORM does.
 */
class OrmFlowBundleRegistryTenantTest {

    private static final long TENANT_A = 7L;
    private static final long TENANT_B = 9L;
    private static final long SHARED = FlowBundleRegistry.SHARED_TENANT_ID;

    private static final String FLOW_A = "100";
    private static final String FLOW_B = "200";
    private static final String FLOW_SHARED = "300";

    private StubBundleService stubService;
    private OrmFlowBundleRegistry registry;
    private DefaultFlowTriggerRegistry triggers;

    @BeforeEach
    void setUp() {
        stubService = new StubBundleService();
        registry = new OrmFlowBundleRegistry(stubService, 60);
        triggers = new DefaultFlowTriggerRegistry(registry);
    }

    // ==================== Trigger index ====================

    @Test
    void firstTenantToFireDoesNotHideOtherTenantsOrSharedFlows() {
        publish(TENANT_A, "a-order", FLOW_A);
        publish(TENANT_B, "b-order", FLOW_B);
        publish(SHARED, "shared-order", FLOW_SHARED);

        assertEquals(List.of("a-order", "shared-order"), firedIn(TENANT_A));
        assertEquals(List.of("b-order", "shared-order"), firedIn(TENANT_B));
        // a context without a tenant (e.g. a cross-tenant cron) fires platform-shared flows only
        Context crossTenant = new Context();
        crossTenant.setCrossTenant(true);
        assertEquals(List.of("shared-order"),
                flowNames(ContextHolder.callWith(crossTenant, () -> triggers.findMatchingFlows(orderCreated()))));
    }

    @Test
    void tenantWithoutFlowsIsQueriedOnceWithinTheTtl() {
        assertTrue(firedIn(TENANT_A).isEmpty());
        assertTrue(firedIn(TENANT_A).isEmpty());
        assertFalse(inTenant(TENANT_A, () -> triggers.hasEntityChangeTrigger("Customer")));

        assertEquals(1, stubService.activeQueries(TENANT_A));
        assertEquals(1, stubService.activeQueries(SHARED));
    }

    @Test
    void expiredEntryIsLoadedAgain() {
        DefaultFlowTriggerRegistry noTtl = new DefaultFlowTriggerRegistry(new OrmFlowBundleRegistry(stubService, 0));

        inTenant(TENANT_A, () -> noTtl.findMatchingFlows(orderCreated()));
        inTenant(TENANT_A, () -> noTtl.findMatchingFlows(orderCreated()));

        assertEquals(2, stubService.activeQueries(TENANT_A));
    }

    @Test
    void publishAndActivateTakeEffectAtOnceOnThisInstance() {
        CompiledFlowDefinition r1 = publish(TENANT_A, "approval", FLOW_A);
        assertEquals(List.of(1), firedRevisionsIn(TENANT_A));
        assertTrue(firedIn(TENANT_B).isEmpty());

        publish(TENANT_A, "approval", FLOW_A);
        assertEquals(List.of(2), firedRevisionsIn(TENANT_A));

        FlowPublishServiceImpl publishService = new FlowPublishServiceImpl(null, registry);
        ReflectionTestUtils.setField(publishService, "bundleService", stubService);
        inTenant(TENANT_A, () -> publishService.activateBundle(r1.getBundleId()));
        assertEquals(List.of(1), firedRevisionsIn(TENANT_A));

        // tenant B's entry was never dropped by tenant A's publishes
        assertEquals(1, stubService.activeQueries(TENANT_B));
    }

    @Test
    void debugRunsAndBundleReadsLeaveTheTriggerIndexAlone() {
        CompiledFlowDefinition published = publish(TENANT_A, "approval", FLOW_A);
        // a bundle read before the first event must not count as "the index is loaded"
        assertTrue(inTenant(TENANT_A, () -> registry.getByBundleId(published.getBundleId())).isPresent());
        assertEquals(List.of("approval"), firedIn(TENANT_A));

        CompiledFlowDefinition debug = inTenant(TENANT_A,
                () -> registry.registerDebug(orderFlow("approval"), null, FLOW_A));
        assertTrue(inTenant(TENANT_A, () -> registry.getByBundleId(debug.getBundleId())).isPresent());

        assertEquals(List.of(1), firedRevisionsIn(TENANT_A));
        assertEquals(1, stubService.activeQueries(TENANT_A));
    }

    @Test
    void changeLogsOfModelsNoFlowWatchesAreNotMapped() {
        publish(TENANT_A, "a-order", FLOW_A);
        List<ChangeLog> changeLogs = List.of(changeLog("Order"), changeLog("Customer"), changeLog("Order"));

        List<FlowTriggerEvent> events = inTenant(TENANT_A, () -> ChangeLogTriggerMapper.mapChangeLogs(
                changeLogs, "1", triggers::hasEntityChangeTrigger));

        assertEquals(List.of("Order", "Order"), events.stream().map(FlowTriggerEvent::getSourceModel).toList());
        assertFalse(inTenant(TENANT_B, () -> triggers.hasEntityChangeTrigger("Order")));
    }

    // ==================== Definitions ====================

    @Test
    void definitionOfAnotherTenantReadsAsAbsent() {
        CompiledFlowDefinition own = publish(TENANT_A, "approval", FLOW_A);
        CompiledFlowDefinition shared = publish(SHARED, "shared", FLOW_SHARED);
        // tenant A's reads put both into the cache
        assertTrue(inTenant(TENANT_A, () -> registry.getByBundleId(own.getBundleId())).isPresent());
        assertTrue(inTenant(TENANT_A, () -> registry.getByBundleId(shared.getBundleId())).isPresent());

        // a cache hit from another tenant is refused; the platform-shared bundle is not
        assertTrue(inTenant(TENANT_B, () -> registry.getByBundleId(own.getBundleId())).isEmpty());
        assertTrue(inTenant(TENANT_B, () -> registry.getByBundleId(shared.getBundleId())).isPresent());

        // the load on a miss applies the same check
        CompiledFlowDefinition other = publish(TENANT_B, "other", FLOW_B);
        assertTrue(inTenant(TENANT_A, () -> registry.getByBundleId(other.getBundleId())).isEmpty());
        assertTrue(inTenant(TENANT_B, () -> registry.getByBundleId(other.getBundleId())).isPresent());

        // a cross-tenant context (maintenance jobs) resolves any bundle
        Context crossTenant = new Context();
        crossTenant.setCrossTenant(true);
        assertTrue(ContextHolder.callWith(crossTenant, () -> registry.getByBundleId(own.getBundleId())).isPresent());
    }

    @Test
    void activeRevisionOfADesignIsReadFromTheDatabase() {
        CompiledFlowDefinition r1 = publish(TENANT_A, "approval", FLOW_A);
        publish(TENANT_A, "approval", FLOW_A);
        assertEquals(2, activeRevision(TENANT_A, FLOW_A));

        // another instance rolls back: nothing is invalidated on this one
        inTenant(TENANT_A, () -> stubService.activateBundle(r1.getBundleId()));
        assertEquals(1, activeRevision(TENANT_A, FLOW_A));

        // other tenants cannot start the design; a platform-shared design starts anywhere
        assertTrue(inTenant(TENANT_B, () -> registry.getActiveByFlowId(FLOW_A)).isEmpty());
        CompiledFlowDefinition shared = publish(SHARED, "shared", FLOW_SHARED);
        assertEquals(shared.getBundleId(),
                inTenant(TENANT_B, () -> registry.getActiveByFlowId(FLOW_SHARED)).orElseThrow().getBundleId());
    }

    @Test
    void bundleListShowsOnlyTheCallersTenant() {
        publish(TENANT_A, "a-flow", FLOW_A);
        publish(TENANT_B, "b-flow", FLOW_B);
        publish(SHARED, "shared-flow", FLOW_SHARED);
        FlowBundleController controller = new FlowBundleController(registry, stubService, null);

        List<FlowBundleSummaryView> listed = inTenant(TENANT_A, () -> controller.list(null)).getData();

        assertEquals(List.of("a-flow"), listed.stream().map(FlowBundleSummaryView::getFlowName).toList());
        assertTrue(inTenant(TENANT_A, () -> controller.list(FLOW_B)).getData().isEmpty());
    }

    // ==================== Helpers ====================

    private CompiledFlowDefinition publish(long tenantId, String flowName, String flowId) {
        return inTenant(tenantId, () -> registry.register(orderFlow(flowName), null, flowId));
    }

    private List<String> firedIn(long tenantId) {
        return flowNames(inTenant(tenantId, () -> triggers.findMatchingFlows(orderCreated())));
    }

    private List<Integer> firedRevisionsIn(long tenantId) {
        return inTenant(tenantId, () -> triggers.findMatchingFlows(orderCreated())).stream()
                .map(CompiledFlowDefinition::getRevision)
                .toList();
    }

    private int activeRevision(long tenantId, String flowId) {
        return inTenant(tenantId, () -> registry.getActiveByFlowId(flowId)).orElseThrow().getRevision();
    }

    private static List<String> flowNames(List<CompiledFlowDefinition> definitions) {
        return definitions.stream().map(CompiledFlowDefinition::getFlowName).toList();
    }

    private static <T> T inTenant(long tenantId, Supplier<T> action) {
        Context context = new Context();
        context.setTenantId(tenantId);
        return ContextHolder.callWith(context, action::get);
    }

    private static FlowTriggerEvent orderCreated() {
        return FlowTriggerEvent.builder()
                .type("EntityChange")
                .sourceModel("Order")
                .parameters(Map.of(ChangeLogTriggerMapper.PARAM_ACCESS_TYPE, "CREATE"))
                .build();
    }

    private static ChangeLog changeLog(String model) {
        ChangeLog changeLog = new ChangeLog();
        changeLog.setModel(model);
        changeLog.setRowId("1");
        changeLog.setAccessType(AccessType.CREATE);
        changeLog.setDataAfterChange(Map.of("id", 1));
        return changeLog;
    }

    private static CompiledFlowDefinition orderFlow(String flowName) {
        return CompiledFlowDefinition.builder()
                .flowName(flowName)
                .scenario(FlowScenario.AUTOMATION)
                .compiledAt(LocalDateTime.now())
                .sync(false)
                .modelName("Order").trigger(new EntityChangeTrigger(List.of(ChangeEvent.CREATE), null))
                .entryNodeIds(List.of("start"))
                .terminalNodeIds(List.of("end"))
                .nodeIndex(Map.of(
                        "start", CompiledFlowNode.builder().nodeId("start").type(FlowNodeType.START).build(),
                        "end", CompiledFlowNode.builder().nodeId("end").type(FlowNodeType.END).build()))
                .transitionIndex(Map.of(
                        "e1", CompiledFlowTransition.builder().edgeId("e1").source("start").target("end").build()))
                .build();
    }
}
