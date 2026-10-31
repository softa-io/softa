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
 * All lookups are keyed on {@code flowId}, mirroring the production registry.
 */
public class StubFlowBundleRegistry implements FlowBundleRegistry {

    private final AtomicLong bundleIdSeq = new AtomicLong(1000);

    /** bundleId → definition */
    private final Map<Long, CompiledFlowDefinition> bundleCache = new ConcurrentHashMap<>();
    /** flowId → active bundleId */
    private final Map<String, Long> activeBundleIndex = new ConcurrentHashMap<>();
    /** flowId → sorted revision map */
    private final Map<String, NavigableMap<Integer, CompiledFlowDefinition>> revisionsByDesign = new ConcurrentHashMap<>();
    /** Flows switched off: their active revision stays, but nothing new starts from it. */
    private final Set<String> disabledFlows = ConcurrentHashMap.newKeySet();

    /** Switches the flow off, as {@code FlowDesign.enabled = false} does for the production registry. */
    public void disable(String flowId) {
        disabledFlows.add(flowId);
    }

    /** Switches the flow back on. */
    public void enable(String flowId) {
        disabledFlows.remove(flowId);
    }

    @Override
    public CompiledFlowDefinition register(CompiledFlowDefinition definition,
                                           DesignFlowDefinition design,
                                           String flowId) {
        if (definition == null) {
            throw new FlowRuntimeException("definition must not be null");
        }
        if (flowId == null) {
            throw new FlowRuntimeException("flowId is required");
        }
        NavigableMap<Integer, CompiledFlowDefinition> revisions =
                revisionsByDesign.computeIfAbsent(flowId, _ -> new TreeMap<>());
        int nextRevision = revisions.isEmpty() ? 1 : revisions.lastKey() + 1;
        long bundleId = bundleIdSeq.getAndIncrement();

        // toBuilder keeps every compiled field, as the production registry does; only revision metadata is stamped.
        CompiledFlowDefinition published = definition.toBuilder()
                .bundleId(bundleId)
                .flowId(flowId)
                .revision(nextRevision)
                .publishedAt(LocalDateTime.now())
                .build();

        revisions.put(nextRevision, published);
        bundleCache.put(bundleId, published);
        activeBundleIndex.put(flowId, bundleId);
        return published;
    }

    @Override
    public Optional<CompiledFlowDefinition> getByBundleId(Long bundleId) {
        return Optional.ofNullable(bundleCache.get(bundleId));
    }

    @Override
    public Optional<CompiledFlowDefinition> getActiveByFlowId(String flowId) {
        Long bundleId = disabledFlows.contains(flowId) ? null : activeBundleIndex.get(flowId);
        return bundleId != null ? getByBundleId(bundleId) : Optional.empty();
    }

    @Override
    public List<CompiledFlowDefinition> listRevisionsByFlowId(String flowId) {
        NavigableMap<Integer, CompiledFlowDefinition> revisions = revisionsByDesign.get(flowId);
        if (revisions == null || revisions.isEmpty()) {
            return List.of();
        }
        return revisions.descendingMap().values().stream().toList();
    }

    @Override
    public Collection<CompiledFlowDefinition> list() {
        return activeBundleIndex.entrySet().stream()
                .filter(entry -> !disabledFlows.contains(entry.getKey()))
                .map(entry -> bundleCache.get(entry.getValue()))
                .filter(Objects::nonNull)
                .sorted(Comparator.comparing(CompiledFlowDefinition::getFlowName,
                        Comparator.nullsLast(Comparator.naturalOrder())))
                .toList();
    }
}
