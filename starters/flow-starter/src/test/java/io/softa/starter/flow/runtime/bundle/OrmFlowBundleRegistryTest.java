package io.softa.starter.flow.runtime.bundle;

import java.time.LocalDateTime;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.dao.DuplicateKeyException;

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
class OrmFlowBundleRegistryTest {

    private static final Long DESIGN_A = 100L;
    private static final Long DESIGN_B = 200L;

    private StubBundleService stubService;
    private OrmFlowBundleRegistry registry;

    @BeforeEach
    void setUp() {
        stubService = new StubBundleService();
        registry = new OrmFlowBundleRegistry(stubService);
        registry.warmUpCache();
    }

    // ==================== FlowBundleMapper Tests ====================

    @Test
    void mapperRoundTrip_preservesAllFields() {
        CompiledFlowDefinition original = sampleDefinition("mapper-flow", 1);

        FlowBundle entity = FlowBundleMapper.toEntity(original, null, null);

        assertEquals("mapper-flow", entity.getFlowCode());
        assertEquals("Mapper flow", entity.getFlowName());
        assertEquals(1, entity.getRevision());
        assertEquals(FlowScenario.PROCESS, entity.getScenario());
        assertTrue(entity.getActive());
        assertNotNull(entity.getCompiledJson());

        CompiledFlowDefinition restored = FlowBundleMapper.toDefinition(entity);

        assertNotNull(restored);
        assertEquals(original.getFlowCode(), restored.getFlowCode());
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
        assertEquals("Order", ((EntityChangeTrigger) restored.getTrigger()).modelName());
    }

    @Test
    void mapperToEntity_updatesExistingEntity() {
        CompiledFlowDefinition def = sampleDefinition("update-flow", 2);
        FlowBundle existing = new FlowBundle();
        existing.setId(99L);

        FlowBundle entity = FlowBundleMapper.toEntity(def, null, existing);

        assertEquals(99L, entity.getId());
        assertEquals("update-flow", entity.getFlowCode());
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
        CompiledFlowDefinition result = registry.register(sampleDefinition("reg-flow", null), null, DESIGN_A);

        assertEquals(1, result.getRevision());
        assertNotNull(result.getPublishedAt());
        assertEquals(DESIGN_A, result.getDesignId());
        assertEquals(1, stubService.savedBundles.size());
    }

    @Test
    void register_incrementsRevisionPerDesignId() {
        registry.register(sampleDefinition("v-flow", null), null, DESIGN_A);
        CompiledFlowDefinition v2 = registry.register(sampleDefinition("v-flow", null), null, DESIGN_A);
        CompiledFlowDefinition v3 = registry.register(sampleDefinition("v-flow", null), null, DESIGN_A);

        assertEquals(2, v2.getRevision());
        assertEquals(3, v3.getRevision());
    }

    @Test
    void register_differentDesignIdsMaintainSeparateRevisions() {
        CompiledFlowDefinition a = registry.register(sampleDefinition("flow-a", null), null, DESIGN_A);
        CompiledFlowDefinition b = registry.register(sampleDefinition("flow-b", null), null, DESIGN_B);
        CompiledFlowDefinition a2 = registry.register(sampleDefinition("flow-a", null), null, DESIGN_A);

        assertEquals(1, a.getRevision());
        assertEquals(1, b.getRevision());
        assertEquals(2, a2.getRevision());
    }

    @Test
    void register_throwsOnNullDesignId() {
        assertThrows(Exception.class, () -> registry.register(sampleDefinition("x", null), null, null));
    }

    @Test
    void register_throwsOnNullDefinition() {
        assertThrows(Exception.class, () -> registry.register(null, null, DESIGN_A));
    }

    @Test
    void getActiveByDesignId_returnsLatestRevision() {
        registry.register(sampleDefinition("get-flow", null), null, DESIGN_A);
        registry.register(sampleDefinition("get-flow", null), null, DESIGN_A);

        Optional<CompiledFlowDefinition> latest = registry.getActiveByDesignId(DESIGN_A);

        assertTrue(latest.isPresent());
        assertEquals(2, latest.get().getRevision());
    }

    @Test
    void getActiveByDesignId_returnsEmptyForUnknownDesign() {
        assertTrue(registry.getActiveByDesignId(999L).isEmpty());
    }

    @Test
    void getByBundleId_returnsSpecificBundle() {
        CompiledFlowDefinition v1 = registry.register(sampleDefinition("bid-flow", null), null, DESIGN_A);
        registry.register(sampleDefinition("bid-flow", null), null, DESIGN_A);

        Optional<CompiledFlowDefinition> found = registry.getByBundleId(v1.getBundleId());

        assertTrue(found.isPresent());
        assertEquals(1, found.get().getRevision());
    }

    @Test
    void listRevisionsByDesignId_returnsAllRevisionsDescending() {
        registry.register(sampleDefinition("hist-flow", null), null, DESIGN_A);
        registry.register(sampleDefinition("hist-flow", null), null, DESIGN_A);
        registry.register(sampleDefinition("hist-flow", null), null, DESIGN_A);

        List<CompiledFlowDefinition> revisions = registry.listRevisionsByDesignId(DESIGN_A);

        assertEquals(3, revisions.size());
        assertEquals(List.of(3, 2, 1), revisions.stream().map(CompiledFlowDefinition::getRevision).toList());
    }

    @Test
    void listRevisionsByDesignId_returnsEmptyForUnknownDesign() {
        assertTrue(registry.listRevisionsByDesignId(999L).isEmpty());
    }

    @Test
    void list_returnsActiveRevisionPerDesign() {
        registry.register(sampleDefinition("list-a", null), null, DESIGN_A);
        registry.register(sampleDefinition("list-a", null), null, DESIGN_A);
        registry.register(sampleDefinition("list-b", null), null, DESIGN_B);

        Collection<CompiledFlowDefinition> all = registry.list();

        assertEquals(2, all.size());
        all.stream()
                .filter(d -> DESIGN_A.equals(d.getDesignId()))
                .forEach(d -> assertEquals(2, d.getRevision()));
    }

    @Test
    void warmUpCache_loadsFromDatabase() {
        CompiledFlowDefinition def = sampleDefinition("preloaded", 1);
        def.setPublishedAt(LocalDateTime.now());
        FlowBundle entity = FlowBundleMapper.toEntity(def, null, null, DESIGN_A);
        entity.setId(1L);
        entity.setDesignId(DESIGN_A);
        stubService.savedBundles.put(1L, entity);

        OrmFlowBundleRegistry freshRegistry = new OrmFlowBundleRegistry(stubService);
        freshRegistry.warmUpCache();

        Optional<CompiledFlowDefinition> loaded = freshRegistry.getActiveByDesignId(DESIGN_A);
        assertTrue(loaded.isPresent());
        assertEquals(1, loaded.get().getRevision());
    }

    @Test
    void register_preservesSyncAndRollbackOnFail() {
        CompiledFlowDefinition def = sampleDefinition("sync-flow", null);
        def.setSync(true);
        def.setRollbackOnFail(true);

        CompiledFlowDefinition result = registry.register(def, null, DESIGN_A);

        assertTrue(result.getSync());
        assertTrue(result.getRollbackOnFail());
        assertTrue(registry.getActiveByDesignId(DESIGN_A).isPresent());
    }

    @Test
    void register_preservesTriggerDefinition() {
        CompiledFlowDefinition result = registry.register(sampleDefinition("trigger-flow", null), null, DESIGN_A);

        assertNotNull(result.getTrigger());
        assertInstanceOf(EntityChangeTrigger.class, result.getTrigger());
        assertEquals("Order", ((EntityChangeTrigger) result.getTrigger()).modelName());
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

        CompiledFlowDefinition published = registry.register(def, null, DESIGN_A);

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
            public int getNextRevision(Long designId) {
                int revision = super.getNextRevision(designId);
                if (nextRevisionCalls.getAndIncrement() == 0) {
                    // a concurrent publish claims this revision between compute and insert
                    FlowBundle winner = new FlowBundle();
                    winner.setDesignId(designId);
                    winner.setRevision(revision);
                    winner.setFlowCode("race-flow");
                    winner.setActive(false);
                    saveBundle(winner);
                }
                return revision;
            }
        };
        OrmFlowBundleRegistry racingRegistry = new OrmFlowBundleRegistry(racingStub);

        CompiledFlowDefinition result = racingRegistry.register(sampleDefinition("race-flow", null), null, DESIGN_A);

        assertEquals(2, result.getRevision());
        assertEquals(2, nextRevisionCalls.get());
    }

    @Test
    void registerDebug_isResolvableButNeverActiveNorListed() {
        CompiledFlowDefinition published = registry.register(sampleDefinition("dbg-flow", null), null, DESIGN_A);
        CompiledFlowDefinition debug = registry.registerDebug(sampleDefinition("dbg-flow", null), null, DESIGN_A);

        // resolvable by id (instances pin it), with its own revision number
        assertTrue(registry.getByBundleId(debug.getBundleId()).isPresent());
        assertEquals(2, debug.getRevision());
        // never the design's effective revision
        assertEquals(published.getBundleId(), registry.getActiveByDesignId(DESIGN_A).orElseThrow().getBundleId());
        // hidden from the revision list
        assertEquals(List.of(1), stubService.listRevisionsByDesignId(DESIGN_A).stream()
                .map(FlowBundle::getRevision).toList());
        // and cannot be activated
        assertThrows(Exception.class, () -> stubService.activateBundle(debug.getBundleId()));
    }

    // ==================== Revision Management Tests ====================

    @Test
    void activateBundle_restoresPreviousRevision() {
        CompiledFlowDefinition v1 = registry.register(sampleDefinition("rb-flow", null), null, DESIGN_A);
        registry.register(sampleDefinition("rb-flow", null), null, DESIGN_A);
        registry.register(sampleDefinition("rb-flow", null), null, DESIGN_A);

        assertEquals(3, registry.getActiveByDesignId(DESIGN_A).orElseThrow().getRevision());

        Optional<FlowBundle> restored = stubService.activateBundle(v1.getBundleId());
        assertTrue(restored.isPresent());
        assertEquals(1, restored.get().getRevision());
        assertTrue(restored.get().getActive());
    }

    @Test
    void activateBundle_returnsEmptyForNonexistentBundle() {
        registry.register(sampleDefinition("rb2-flow", null), null, DESIGN_A);
        assertTrue(stubService.activateBundle(99999L).isEmpty());
    }

    // ==================== Helpers ====================

    private static CompiledFlowDefinition sampleDefinition(String flowCode, Integer revision) {
        String flowName = Character.toUpperCase(flowCode.charAt(0))
                + flowCode.substring(1).replace("-", " ").replace("_", " ");
        return CompiledFlowDefinition.builder()
                .flowCode(flowCode)
                .flowName(flowName)
                .scenario(FlowScenario.PROCESS)
                .compiledAt(LocalDateTime.now())
                .revision(revision)
                .sync(false)
                .rollbackOnFail(false)
                .trigger(new EntityChangeTrigger("Order", null, null))
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
                        .hasApproval(false)
                        .hasSubflow(false)
                        .hasParallelGateway(false)
                        .hasLoop(false)
                        .build())
                .build();
    }

    // ==================== Stub Service ====================

    static class StubBundleService implements FlowBundleService {

        final Map<Long, FlowBundle> savedBundles = new ConcurrentHashMap<>();
        private final AtomicLong idSequence = new AtomicLong(1);

        @Override
        public Optional<FlowBundle> findById(Long id) {
            return Optional.ofNullable(savedBundles.get(id));
        }

        @Override
        public void saveBundle(FlowBundle bundle) {
            if (bundle.getId() == null) {
                // mimic uk_tenant_design_revision: a create colliding on (designId, revision) fails
                boolean revisionTaken = bundle.getDesignId() != null
                        && findByDesignIdAndRevision(bundle.getDesignId(), bundle.getRevision()).isPresent();
                if (revisionTaken) {
                    throw new DuplicateKeyException("uk_tenant_design_revision");
                }
                bundle.setId(idSequence.getAndIncrement());
            }
            savedBundles.put(bundle.getId(), bundle);
        }

        @Override
        public Optional<FlowBundle> findActiveByDesignId(Long designId) {
            return savedBundles.values().stream()
                    .filter(b -> designId.equals(b.getDesignId())
                            && Boolean.TRUE.equals(b.getActive()))
                    .max(Comparator.comparingInt(FlowBundle::getRevision));
        }

        @Override
        public Optional<FlowBundle> findByDesignIdAndRevision(Long designId, Integer revision) {
            return savedBundles.values().stream()
                    .filter(b -> designId.equals(b.getDesignId()) && revision.equals(b.getRevision()))
                    .findFirst();
        }

        @Override
        public List<FlowBundle> listRevisionsByDesignId(Long designId) {
            return savedBundles.values().stream()
                    .filter(b -> designId.equals(b.getDesignId()))
                    .filter(b -> !Boolean.TRUE.equals(b.getDebug()))
                    .sorted(Comparator.comparingInt(FlowBundle::getRevision).reversed())
                    .toList();
        }

        @Override
        public List<FlowBundle> getAllActiveFlow() {
            Map<Long, FlowBundle> latestMap = new HashMap<>();
            for (FlowBundle b : savedBundles.values()) {
                if (Boolean.TRUE.equals(b.getActive()) && b.getDesignId() != null) {
                    latestMap.merge(b.getDesignId(), b,
                            (a, c) -> a.getRevision() >= c.getRevision() ? a : c);
                }
            }
            return new ArrayList<>(latestMap.values());
        }

        @Override
        public int getNextRevision(Long designId) {
            return savedBundles.values().stream()
                    .filter(b -> designId.equals(b.getDesignId()))
                    .mapToInt(FlowBundle::getRevision)
                    .max()
                    .orElse(0) + 1;
        }

        @Override
        public void markAsActive(Long designId, Integer revision) {
            for (FlowBundle b : savedBundles.values()) {
                if (designId.equals(b.getDesignId())) {
                    b.setActive(revision.equals(b.getRevision()));
                }
            }
        }

        @Override
        public Optional<FlowBundle> activateBundle(Long bundleId) {
            Optional<FlowBundle> target = findById(bundleId);
            if (target.isEmpty()) return Optional.empty();
            FlowBundle bundle = target.get();
            if (Boolean.TRUE.equals(bundle.getDebug())) {
                throw new IllegalStateException("Debug bundle cannot be activated");
            }
            markAsActive(bundle.getDesignId(), bundle.getRevision());
            return target;
        }
    }
}
