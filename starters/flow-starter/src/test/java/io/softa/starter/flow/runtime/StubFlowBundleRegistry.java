package io.softa.starter.flow.runtime;

import java.time.LocalDateTime;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;

import io.softa.starter.flow.design.DesignFlowDefinition;
import io.softa.starter.flow.runtime.bundle.CompiledFlowDefinition;
import io.softa.starter.flow.runtime.bundle.FlowBundleRegistry;
import io.softa.starter.flow.runtime.exception.FlowRuntimeException;

/**
 * Lightweight test-only bundle registry backed by in-memory maps.
 * All lookups are keyed on {@code designId}, mirroring the production registry.
 */
public class StubFlowBundleRegistry implements FlowBundleRegistry {

    private final AtomicLong bundleIdSeq = new AtomicLong(1000);

    /** bundleId → definition */
    private final Map<Long, CompiledFlowDefinition> bundleCache = new ConcurrentHashMap<>();
    /** designId → active bundleId */
    private final Map<Long, Long> activeBundleIndex = new ConcurrentHashMap<>();
    /** designId → sorted revision map */
    private final Map<Long, NavigableMap<Integer, CompiledFlowDefinition>> revisionsByDesign = new ConcurrentHashMap<>();

    @Override
    public CompiledFlowDefinition register(CompiledFlowDefinition definition,
                                           DesignFlowDefinition design,
                                           Long designId) {
        if (definition == null) {
            throw new FlowRuntimeException("definition must not be null");
        }
        if (designId == null) {
            throw new FlowRuntimeException("designId is required");
        }
        NavigableMap<Integer, CompiledFlowDefinition> revisions =
                revisionsByDesign.computeIfAbsent(designId, _ -> new TreeMap<>());
        int nextRevision = revisions.isEmpty() ? 1 : revisions.lastKey() + 1;
        long bundleId = bundleIdSeq.getAndIncrement();

        CompiledFlowDefinition published = CompiledFlowDefinition.builder()
                .bundleId(bundleId)
                .designId(designId)
                .flowCode(definition.getFlowCode())
                .flowName(definition.getFlowName())
                .scenario(definition.getScenario())
                .compiledAt(definition.getCompiledAt())
                .revision(nextRevision)
                .publishedAt(LocalDateTime.now())
                .trigger(definition.getTrigger())
                .forms(definition.getForms())
                .allowInitiatorWithdraw(definition.getAllowInitiatorWithdraw())
                .sync(definition.getSync())
                .rollbackOnFail(definition.getRollbackOnFail())
                .approverDedup(definition.getApproverDedup())
                .declaredOutputs(definition.getDeclaredOutputs())
                .entryNodeIds(definition.getEntryNodeIds())
                .terminalNodeIds(definition.getTerminalNodeIds())
                .topologicalOrder(definition.getTopologicalOrder())
                .nodeIndex(definition.getNodeIndex())
                .transitionIndex(definition.getTransitionIndex())
                .capabilitySummary(definition.getCapabilitySummary())
                .build();

        revisions.put(nextRevision, published);
        bundleCache.put(bundleId, published);
        activeBundleIndex.put(designId, bundleId);
        return published;
    }

    @Override
    public Optional<CompiledFlowDefinition> getByBundleId(Long bundleId) {
        return Optional.ofNullable(bundleCache.get(bundleId));
    }

    @Override
    public Optional<CompiledFlowDefinition> getActiveByDesignId(Long designId) {
        Long bundleId = activeBundleIndex.get(designId);
        return bundleId != null ? getByBundleId(bundleId) : Optional.empty();
    }

    @Override
    public List<CompiledFlowDefinition> listRevisionsByDesignId(Long designId) {
        NavigableMap<Integer, CompiledFlowDefinition> revisions = revisionsByDesign.get(designId);
        if (revisions == null || revisions.isEmpty()) {
            return List.of();
        }
        return revisions.descendingMap().values().stream().toList();
    }

    @Override
    public Collection<CompiledFlowDefinition> list() {
        return activeBundleIndex.values().stream()
                .map(bundleCache::get)
                .filter(Objects::nonNull)
                .sorted(Comparator.comparing(CompiledFlowDefinition::getFlowCode,
                        Comparator.nullsLast(Comparator.naturalOrder())))
                .toList();
    }
}
