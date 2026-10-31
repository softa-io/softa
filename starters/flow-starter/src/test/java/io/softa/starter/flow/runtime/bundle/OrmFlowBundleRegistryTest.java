package io.softa.starter.flow.runtime.bundle;

import java.time.LocalDateTime;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import java.util.stream.Stream;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.dao.DuplicateKeyException;

import io.softa.framework.base.context.Context;
import io.softa.framework.base.context.ContextHolder;
import io.softa.starter.flow.api.CompiledFlowCapabilitySummary;
import io.softa.starter.flow.design.trigger.EntityChangeTrigger;
import io.softa.starter.flow.entity.FlowBundle;
import io.softa.starter.flow.enums.ApproverDedupStrategy;
import io.softa.starter.flow.enums.FlowNodeType;
import io.softa.starter.flow.enums.FlowScenario;
import io.softa.starter.flow.runtime.nodeconfig.ScriptNodeConfig;
import io.softa.starter.flow.service.FlowBundleService;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Tests for {@link OrmFlowBundleRegistry}, {@link FlowBundleMapper},
 * and their interaction with the persistence layer.
 */
public class OrmFlowBundleRegistryTest {

    private static final String FLOW_A = "100";
    private static final String FLOW_B = "200";

    private StubBundleService stubService;
    private OrmFlowBundleRegistry registry;

    @BeforeEach
    void setUp() {
        stubService = new StubBundleService();
        registry = new OrmFlowBundleRegistry(stubService, 60);
    }

    // ==================== FlowBundleMapper Tests ====================

    @Test
    void mapperRoundTrip_preservesAllFields() {
        CompiledFlowDefinition original = sampleDefinition("mapper-flow", 1);

        FlowBundle entity = FlowBundleMapper.toEntity(original, null, null);

        assertEquals("Mapper flow", entity.getFlowName());
        assertEquals(1, entity.getRevision());
        assertEquals(FlowScenario.AUTOMATION, entity.getScenario());
        assertTrue(entity.getActive());
        assertNotNull(entity.getCompiledJson());

        CompiledFlowDefinition restored = FlowBundleMapper.toDefinition(entity);

        assertNotNull(restored);
        assertEquals(original.getFlowName(), restored.getFlowName());
        assertEquals(original.getRevision(), restored.getRevision());
        assertEquals(original.getScenario(), restored.getScenario());
        assertEquals(original.getSync(), restored.getSync());
        assertEquals(original.getRollbackOnFail(), restored.getRollbackOnFail());
        assertEquals(original.getEntryNodeIds(), restored.getEntryNodeIds());
        assertEquals(original.getTerminalNodeIds(), restored.getTerminalNodeIds());
        // topologicalOrder is a compile-time output, intentionally not persisted (@JsonIgnore).
        assertNull(restored.getTopologicalOrder());
        assertNotNull(restored.getTrigger());
        assertInstanceOf(EntityChangeTrigger.class, restored.getTrigger());
        assertEquals("Order", restored.getModelName());
    }

    @Test
    void mapperToEntity_updatesExistingEntity() {
        CompiledFlowDefinition def = sampleDefinition("update-flow", 2);
        FlowBundle existing = new FlowBundle();
        existing.setId(99L);

        FlowBundle entity = FlowBundleMapper.toEntity(def, null, existing);

        assertEquals(99L, entity.getId());
        assertEquals("Update flow", entity.getFlowName());
    }

    @Test
    void mapperToDefinition_returnsNullForNullEntity() {
        assertNull(FlowBundleMapper.toDefinition(null));
    }

    @Test
    void mapperToDefinition_returnsNullForBlankJson() {
        FlowBundle entity = new FlowBundle();
        entity.setCompiledJson("  ");
        assertNull(FlowBundleMapper.toDefinition(entity));
    }

    @Test
    void mapperToDefinition_returnsNullForInvalidJson() {
        FlowBundle entity = new FlowBundle();
        entity.setCompiledJson("{invalid-json!!");
        assertNull(FlowBundleMapper.toDefinition(entity));
    }

    // ==================== OrmFlowBundleRegistry Tests ====================

    @Test
    void register_assignsRevisionAndPersists() {
        CompiledFlowDefinition result = registry.register(sampleDefinition("reg-flow", null), null, FLOW_A);

        assertEquals(1, result.getRevision());
        assertNotNull(result.getPublishedAt());
        assertEquals(FLOW_A, result.getFlowId());
        assertEquals(1, stubService.savedBundles.size());
    }

    @Test
    void register_incrementsRevisionPerFlowId() {
        registry.register(sampleDefinition("v-flow", null), null, FLOW_A);
        CompiledFlowDefinition v2 = registry.register(sampleDefinition("v-flow", null), null, FLOW_A);
        CompiledFlowDefinition v3 = registry.register(sampleDefinition("v-flow", null), null, FLOW_A);

        assertEquals(2, v2.getRevision());
        assertEquals(3, v3.getRevision());
    }

    @Test
    void register_differentFlowIdsMaintainSeparateRevisions() {
        CompiledFlowDefinition a = registry.register(sampleDefinition("flow-a", null), null, FLOW_A);
        CompiledFlowDefinition b = registry.register(sampleDefinition("flow-b", null), null, FLOW_B);
        CompiledFlowDefinition a2 = registry.register(sampleDefinition("flow-a", null), null, FLOW_A);

        assertEquals(1, a.getRevision());
        assertEquals(1, b.getRevision());
        assertEquals(2, a2.getRevision());
    }

    @Test
    void register_throwsOnNullFlowId() {
        assertThrows(Exception.class, () -> registry.register(sampleDefinition("x", null), null, null));
    }

    @Test
    void register_throwsOnNullDefinition() {
        assertThrows(Exception.class, () -> registry.register(null, null, FLOW_A));
    }

    @Test
    void getActiveByFlowId_returnsLatestRevision() {
        registry.register(sampleDefinition("get-flow", null), null, FLOW_A);
        registry.register(sampleDefinition("get-flow", null), null, FLOW_A);

        Optional<CompiledFlowDefinition> latest = registry.getActiveByFlowId(FLOW_A);

        assertTrue(latest.isPresent());
        assertEquals(2, latest.get().getRevision());
    }

    @Test
    void getActiveByFlowId_returnsEmptyForUnknownDesign() {
        assertTrue(registry.getActiveByFlowId("999").isEmpty());
    }

    @Test
    void getByBundleId_returnsSpecificBundle() {
        CompiledFlowDefinition v1 = registry.register(sampleDefinition("bid-flow", null), null, FLOW_A);
        registry.register(sampleDefinition("bid-flow", null), null, FLOW_A);

        Optional<CompiledFlowDefinition> found = registry.getByBundleId(v1.getBundleId());

        assertTrue(found.isPresent());
        assertEquals(1, found.get().getRevision());
    }

    @Test
    void listRevisionsByFlowId_returnsAllRevisionsDescending() {
        registry.register(sampleDefinition("hist-flow", null), null, FLOW_A);
        registry.register(sampleDefinition("hist-flow", null), null, FLOW_A);
        registry.register(sampleDefinition("hist-flow", null), null, FLOW_A);

        List<CompiledFlowDefinition> revisions = registry.listRevisionsByFlowId(FLOW_A);

        assertEquals(3, revisions.size());
        assertEquals(List.of(3, 2, 1), revisions.stream().map(CompiledFlowDefinition::getRevision).toList());
    }

    @Test
    void listRevisionsByFlowId_returnsEmptyForUnknownDesign() {
        assertTrue(registry.listRevisionsByFlowId("999").isEmpty());
    }

    @Test
    void list_returnsActiveRevisionPerDesign() {
        registry.register(sampleDefinition("list-a", null), null, FLOW_A);
        registry.register(sampleDefinition("list-a", null), null, FLOW_A);
        registry.register(sampleDefinition("list-b", null), null, FLOW_B);

        Collection<CompiledFlowDefinition> all = registry.list();

        assertEquals(2, all.size());
        all.stream()
                .filter(d -> FLOW_A.equals(d.getFlowId()))
                .forEach(d -> assertEquals(2, d.getRevision()));
    }

    @Test
    void getActiveByFlowId_readsStoredBundlesWithoutPreload() {
        CompiledFlowDefinition def = sampleDefinition("preloaded", 1);
        def.setPublishedAt(LocalDateTime.now());
        FlowBundle entity = FlowBundleMapper.toEntity(def, null, null, FLOW_A);
        entity.setId(1L);
        entity.setFlowId(FLOW_A);
        stubService.savedBundles.put(1L, entity);

        OrmFlowBundleRegistry freshRegistry = new OrmFlowBundleRegistry(stubService, 60);

        Optional<CompiledFlowDefinition> loaded = freshRegistry.getActiveByFlowId(FLOW_A);
        assertTrue(loaded.isPresent());
        assertEquals(1, loaded.get().getRevision());
    }

    @Test
    void register_preservesSyncAndRollbackOnFail() {
        CompiledFlowDefinition def = sampleDefinition("sync-flow", null);
        def.setSync(true);
        def.setRollbackOnFail(true);

        CompiledFlowDefinition result = registry.register(def, null, FLOW_A);

        assertTrue(result.getSync());
        assertTrue(result.getRollbackOnFail());
        assertTrue(registry.getActiveByFlowId(FLOW_A).isPresent());
    }

    @Test
    void register_preservesTriggerDefinition() {
        CompiledFlowDefinition result = registry.register(sampleDefinition("trigger-flow", null), null, FLOW_A);

        assertNotNull(result.getTrigger());
        assertInstanceOf(EntityChangeTrigger.class, result.getTrigger());
        assertEquals("Order", result.getModelName());
    }

    @Test
    void mapperToDefinition_rebuildsParsedConfig() {
        CompiledFlowDefinition original = sampleDefinition("cfg-flow", 1);
        Map<String, Object> scriptConfig = Map.of("expression", "1 + 1", "outputVariable", "sum");
        original.setNodeIndex(Map.of("script1", CompiledFlowNode.builder()
                .nodeId("script1")
                .type(FlowNodeType.SCRIPT)
                .config(scriptConfig)
                .build()));

        FlowBundle entity = FlowBundleMapper.toEntity(original, null, null);
        CompiledFlowDefinition restored = FlowBundleMapper.toDefinition(entity);

        // parsedConfig is @JsonIgnore — it must be rebuilt from the raw config on load
        Object parsed = restored.getNodeIndex().get("script1").getParsedConfig();
        assertInstanceOf(ScriptNodeConfig.class, parsed);
        assertEquals("1 + 1", ((ScriptNodeConfig) parsed).getExpression());
    }

    @Test
    void register_preservesApproverDedupAndDeclaredOutputs() {
        CompiledFlowDefinition def = sampleDefinition("dedup-flow", null);
        def.setApproverDedup(ApproverDedupStrategy.CONTIGUOUS);
        def.setDeclaredOutputs(List.of("result"));

        CompiledFlowDefinition published = registry.register(def, null, FLOW_A);

        assertEquals(ApproverDedupStrategy.CONTIGUOUS, published.getApproverDedup());
        assertEquals(List.of("result"), published.getDeclaredOutputs());

        CompiledFlowDefinition reloaded = FlowBundleMapper.toDefinition(
                stubService.savedBundles.get(published.getBundleId()));
        assertEquals(ApproverDedupStrategy.CONTIGUOUS, reloaded.getApproverDedup());
        assertEquals(List.of("result"), reloaded.getDeclaredOutputs());
    }

    @Test
    void register_retriesRevisionWhenClaimedConcurrently() {
        AtomicInteger nextRevisionCalls = new AtomicInteger();
        StubBundleService racingStub = new StubBundleService() {
            @Override
            public int getNextRevision(String flowId) {
                int revision = super.getNextRevision(flowId);
                if (nextRevisionCalls.getAndIncrement() == 0) {
                    // a concurrent publish claims this revision between compute and insert
                    FlowBundle winner = new FlowBundle();
                    winner.setFlowId(flowId);
                    winner.setRevision(revision);
                    winner.setFlowName("Race flow");
                    winner.setActive(false);
                    saveBundle(winner);
                }
                return revision;
            }
        };
        OrmFlowBundleRegistry racingRegistry = new OrmFlowBundleRegistry(racingStub, 60);

        CompiledFlowDefinition result = racingRegistry.register(sampleDefinition("race-flow", null), null, FLOW_A);

        assertEquals(2, result.getRevision());
        assertEquals(2, nextRevisionCalls.get());
    }

    @Test
    void registerDebug_isResolvableButNeverActiveNorListed() {
        CompiledFlowDefinition published = registry.register(sampleDefinition("dbg-flow", null), null, FLOW_A);
        CompiledFlowDefinition debug = registry.registerDebug(sampleDefinition("dbg-flow", null), null, FLOW_A);

        // resolvable by id (instances pin it), with its own revision number
        assertTrue(registry.getByBundleId(debug.getBundleId()).isPresent());
        assertEquals(2, debug.getRevision());
        // never the design's effective revision
        assertEquals(published.getBundleId(), registry.getActiveByFlowId(FLOW_A).orElseThrow().getBundleId());
        // hidden from the revision list
        assertEquals(List.of(1), stubService.listRevisionsByFlowId(FLOW_A).stream()
                .map(FlowBundle::getRevision).toList());
        // and cannot be activated
        assertThrows(Exception.class, () -> stubService.activateBundle(debug.getBundleId()));
    }

    // ==================== Revision Management Tests ====================

    @Test
    void activateBundle_restoresPreviousRevision() {
        CompiledFlowDefinition v1 = registry.register(sampleDefinition("rb-flow", null), null, FLOW_A);
        registry.register(sampleDefinition("rb-flow", null), null, FLOW_A);
        registry.register(sampleDefinition("rb-flow", null), null, FLOW_A);

        assertEquals(3, registry.getActiveByFlowId(FLOW_A).orElseThrow().getRevision());

        Optional<FlowBundle> restored = stubService.activateBundle(v1.getBundleId());
        assertTrue(restored.isPresent());
        assertEquals(1, restored.get().getRevision());
        assertTrue(restored.get().getActive());
    }

    @Test
    void activateBundle_returnsEmptyForNonexistentBundle() {
        registry.register(sampleDefinition("rb2-flow", null), null, FLOW_A);
        assertTrue(stubService.activateBundle(99999L).isEmpty());
    }

    // ==================== Helpers ====================

    private static CompiledFlowDefinition sampleDefinition(String slug, Integer revision) {
        String flowName = Character.toUpperCase(slug.charAt(0))
                + slug.substring(1).replace("-", " ").replace("_", " ");
        return CompiledFlowDefinition.builder()
                .flowName(flowName)
                .scenario(FlowScenario.AUTOMATION)
                .compiledAt(LocalDateTime.now())
                .revision(revision)
                .sync(false)
                .rollbackOnFail(false)
                .modelName("Order").trigger(new EntityChangeTrigger(null, null))
                .entryNodeIds(List.of("start"))
                .terminalNodeIds(List.of("end"))
                .topologicalOrder(List.of("start", "task1", "end"))
                .nodeIndex(Map.of(
                        "start", CompiledFlowNode.builder().nodeId("start").type(FlowNodeType.START).label("Start").build(),
                        "task1", CompiledFlowNode.builder().nodeId("task1").type(FlowNodeType.CALL_SERVICE).label("Task 1").build(),
                        "end", CompiledFlowNode.builder().nodeId("end").type(FlowNodeType.END).label("End").build()
                ))
                .transitionIndex(Map.of(
                        "e1", CompiledFlowTransition.builder().edgeId("e1").source("start").target("task1").build(),
                        "e2", CompiledFlowTransition.builder().edgeId("e2").source("task1").target("end").build()
                ))
                .capabilitySummary(CompiledFlowCapabilitySummary.builder()
                        .hasSubflow(false)
                        .hasParallelGateway(false)
                        .hasLoop(false)
                        .build())
                .build();
    }

    // ==================== Stub Service ====================

    /**
     * In-memory {@link FlowBundleService} that isolates tenants the way the ORM does: a created
     * row takes the context's tenant, and reads see only that tenant's rows unless the context
     * is cross-tenant. Public for the tests of other packages.
     */
    public static class StubBundleService implements FlowBundleService {

        final Map<Long, FlowBundle> savedBundles = new ConcurrentHashMap<>();
        private final AtomicLong idSequence = new AtomicLong(1);
        /** tenantId → getAllActiveFlow calls made in that tenant's context. */
        private final Map<Long, Integer> activeQueries = new HashMap<>();

        int activeQueries(Long tenantId) {
            return activeQueries.getOrDefault(tenantId, 0);
        }

        private static boolean visible(FlowBundle bundle) {
            Context context = ContextHolder.getContext();
            return context.isCrossTenant() || Objects.equals(bundle.getTenantId(), context.getTenantId());
        }

        private Stream<FlowBundle> visibleBundles() {
            return savedBundles.values().stream().filter(StubBundleService::visible);
        }

        @Override
        public Optional<FlowBundle> findById(Long id) {
            return Optional.ofNullable(savedBundles.get(id)).filter(StubBundleService::visible);
        }

        @Override
        public void saveBundle(FlowBundle bundle) {
            if (bundle.getId() == null) {
                Context context = ContextHolder.getContext();
                if (!context.isCrossTenant()) {
                    bundle.setTenantId(context.getTenantId());
                }
                // mimic uk_tenant_flow_revision: a create colliding on (flowId, revision) fails
                boolean revisionTaken = bundle.getFlowId() != null
                        && findByFlowIdAndRevision(bundle.getFlowId(), bundle.getRevision()).isPresent();
                if (revisionTaken) {
                    throw new DuplicateKeyException("uk_tenant_flow_revision");
                }
                bundle.setId(idSequence.getAndIncrement());
            }
            savedBundles.put(bundle.getId(), bundle);
        }

        @Override
        public Optional<FlowBundle> findActiveByFlowId(String flowId) {
            return visibleBundles()
                    .filter(b -> flowId.equals(b.getFlowId())
                            && Boolean.TRUE.equals(b.getActive()))
                    .max(Comparator.comparingInt(FlowBundle::getRevision));
        }

        @Override
        public Optional<Long> findActiveBundleId(String flowId) {
            return findActiveByFlowId(flowId).map(FlowBundle::getId);
        }

        // Fake-internal helper (no longer part of FlowBundleService).
        private Optional<FlowBundle> findByFlowIdAndRevision(String flowId, Integer revision) {
            return visibleBundles()
                    .filter(b -> flowId.equals(b.getFlowId()) && revision.equals(b.getRevision()))
                    .findFirst();
        }

        @Override
        public List<FlowBundle> listRevisionsByFlowId(String flowId) {
            return visibleBundles()
                    .filter(b -> flowId.equals(b.getFlowId()))
                    .filter(b -> !Boolean.TRUE.equals(b.getDebug()))
                    .sorted(Comparator.comparingInt(FlowBundle::getRevision).reversed())
                    .toList();
        }

        @Override
        public List<FlowBundle> getAllActiveFlow() {
            activeQueries.merge(ContextHolder.getContext().getTenantId(), 1, Integer::sum);
            return visibleBundles()
                    .filter(b -> Boolean.TRUE.equals(b.getActive()) && b.getFlowId() != null)
                    .toList();
        }

        @Override
        public List<FlowBundle> listActiveCronBundles() {
            return visibleBundles()
                    .filter(b -> Boolean.TRUE.equals(b.getActive()) && b.getCronExpression() != null)
                    .toList();
        }

        @Override
        public int getNextRevision(String flowId) {
            return visibleBundles()
                    .filter(b -> flowId.equals(b.getFlowId()))
                    .mapToInt(FlowBundle::getRevision)
                    .max()
                    .orElse(0) + 1;
        }

        @Override
        public void markAsActive(String flowId, Integer revision) {
            visibleBundles()
                    .filter(b -> flowId.equals(b.getFlowId()))
                    .forEach(b -> b.setActive(revision.equals(b.getRevision())));
        }

        @Override
        public Optional<FlowBundle> activateBundle(Long bundleId) {
            Optional<FlowBundle> target = findById(bundleId);
            if (target.isEmpty()) return Optional.empty();
            FlowBundle bundle = target.get();
            if (Boolean.TRUE.equals(bundle.getDebug())) {
                throw new IllegalStateException("Debug bundle cannot be activated");
            }
            markAsActive(bundle.getFlowId(), bundle.getRevision());
            return target;
        }
    }
}
