package io.softa.starter.flow.runtime.bundle;

import java.time.LocalDateTime;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;
import java.util.stream.Collectors;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Primary;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;

import io.softa.framework.base.context.Context;
import io.softa.framework.base.context.ContextHolder;
import io.softa.framework.base.context.ContextUtils;
import io.softa.starter.flow.design.DesignFlowDefinition;
import io.softa.starter.flow.entity.FlowBundle;
import io.softa.starter.flow.runtime.exception.FlowRuntimeException;
import io.softa.starter.flow.service.FlowBundleService;

/**
 * ORM-backed {@link FlowBundleRegistry} for a multi-tenant deployment: nothing is loaded at
 * startup, and every answer is scoped to the current tenant.
 * <ul>
 *   <li><b>Trigger index</b> — per tenant, the active bundles of its enabled flows, bucketed by the model
 *       whose records they start on. Loaded in that tenant's own context the first time one of its events asks (an
 *       empty result is kept too) and reused for {@code flow.trigger-cache-ttl-seconds} (default 60).
 *       Publishing, activating, disabling or enabling on this instance drops the tenant's entry at once; other
 *       instances pick the change up within the TTL. Platform-shared bundles have an entry of
 *       their own, merged into every tenant's candidates.</li>
 *   <li><b>Definitions</b> — {@code bundleId → definition}, a bounded LRU. A published revision
 *       never changes, so entries never go stale; a debug purge evicts its bundle. Every read is
 *       checked against the current tenant.</li>
 *   <li>The active revision of a design is not cached: it is one indexed lookup, and its
 *       definition then comes from the LRU.</li>
 * </ul>
 */
@Slf4j
@Primary
@Component
public class OrmFlowBundleRegistry implements FlowBundleRegistry {

    private static final int MAX_REVISION_RETRIES = 3;

    private static final int MAX_CACHED_DEFINITIONS = 2000;

    /** Index bucket of flows started on no record (schedule, API call, parent flow): candidates for no event. */
    private static final String NO_RECORD = "";

    private static final Comparator<CompiledFlowDefinition> BY_FLOW_NAME = Comparator.comparing(
            CompiledFlowDefinition::getFlowName, Comparator.nullsLast(Comparator.naturalOrder()));

    private final FlowBundleService bundleService;

    private final long triggerCacheTtlNanos;

    /** bundleId → definition, least recently used evicted first. */
    private final Map<Long, CompiledFlowDefinition> definitions = Collections.synchronizedMap(
            new LinkedHashMap<Long, CompiledFlowDefinition>(16, 0.75f, true) {
                @Override
                protected boolean removeEldestEntry(Map.Entry<Long, CompiledFlowDefinition> eldest) {
                    return size() > MAX_CACHED_DEFINITIONS;
                }
            });

    /** tenantId → that tenant's active bundles, for trigger matching. */
    private final ConcurrentHashMap<Long, TenantFlows> triggerIndex = new ConcurrentHashMap<>();

    public OrmFlowBundleRegistry(FlowBundleService bundleService,
                                 @Value("${flow.trigger-cache-ttl-seconds:60}") long triggerCacheTtlSeconds) {
        this.bundleService = bundleService;
        this.triggerCacheTtlNanos = TimeUnit.SECONDS.toNanos(triggerCacheTtlSeconds);
    }

    @Override
    public CompiledFlowDefinition register(CompiledFlowDefinition definition,
                                           DesignFlowDefinition design,
                                           String flowId) {
        return doRegister(definition, design, flowId, false);
    }

    @Override
    public CompiledFlowDefinition registerDebug(CompiledFlowDefinition definition,
                                                DesignFlowDefinition design,
                                                String flowId) {
        return doRegister(definition, design, flowId, true);
    }

    private CompiledFlowDefinition doRegister(CompiledFlowDefinition definition,
                                              DesignFlowDefinition design,
                                              String flowId,
                                              boolean debug) {
        if (definition == null) {
            throw new FlowRuntimeException("Compiled flow definition must not be null");
        }
        if (flowId == null) {
            throw new FlowRuntimeException("flowId is required to register a flow bundle");
        }

        // Revision allocation races with concurrent publishes of the same design: the
        // uk_tenant_flow_revision unique index rejects the loser, which retries with
        // a freshly computed revision instead of silently overwriting the winner's row.
        for (int attempt = 1; attempt <= MAX_REVISION_RETRIES; attempt++) {
            int nextRevision = bundleService.getNextRevision(flowId);

            // toBuilder keeps every compiled field (a hand-written rebuild silently
            // dropped newly added ones); only revision metadata is stamped here.
            CompiledFlowDefinition published = definition.toBuilder()
                    .revision(nextRevision)
                    .flowId(flowId)
                    .publishedAt(LocalDateTime.now())
                    .build();

            FlowBundle entity = FlowBundleMapper.toEntity(published, design, null, flowId);
            if (debug) {
                // resolvable like any bundle, but never the design's effective revision
                entity.setActive(false);
                entity.setDebug(true);
            }
            try {
                bundleService.saveBundle(entity);
            } catch (DuplicateKeyException e) {
                log.info("Revision {} for design {} was claimed concurrently, retrying ({}/{})",
                        nextRevision, flowId, attempt, MAX_REVISION_RETRIES);
                continue;
            }

            // Not put into the definition cache: the first read loads the stored row, which
            // carries the owning tenant this object lacks.
            published.setBundleId(entity.getId());
            if (!debug) {
                bundleService.markAsActive(flowId, nextRevision);
                invalidateTriggerIndex();
            }

            log.debug("Registered flow bundle: bundleId={} flowId={} r{} debug={}",
                    entity.getId(), flowId, nextRevision, debug);
            return published;
        }
        throw new FlowRuntimeException(
                "Failed to allocate a publish revision for design " + flowId + " after "
                        + MAX_REVISION_RETRIES + " attempts (concurrent publishes)");
    }

    @Override
    public Optional<CompiledFlowDefinition> getByBundleId(Long bundleId) {
        if (bundleId == null) {
            return Optional.empty();
        }
        CompiledFlowDefinition definition = definitions.get(bundleId);
        if (definition == null) {
            // Bundle ids are globally unique: read the row by key regardless of tenant and of the
            // caller's row permissions, then apply the same tenant check a cache hit gets.
            definition = ContextUtils.inSystemContext(() -> bundleService.findById(bundleId))
                    .map(FlowBundleMapper::toDefinition)
                    .orElse(null);
            if (definition == null) {
                return Optional.empty();
            }
            definitions.put(bundleId, definition);
        }
        return isVisible(definition) ? Optional.of(definition) : Optional.empty();
    }

    @Override
    public Optional<CompiledFlowDefinition> getActiveByFlowId(String flowId) {
        // Not cached: the active revision moves on publish / activate on any instance, and this is
        // one indexed lookup in the caller's tenant. A design not found there may be platform-shared.
        Optional<Long> bundleId = bundleService.findActiveBundleId(flowId);
        if (bundleId.isEmpty() && !SHARED_TENANT_ID.equals(ContextHolder.getContext().getTenantId())) {
            bundleId = ContextUtils.inTenantContext(SHARED_TENANT_ID,
                    () -> bundleService.findActiveBundleId(flowId));
        }
        return bundleId.flatMap(this::getByBundleId);
    }

    @Override
    public List<CompiledFlowDefinition> listRevisionsByFlowId(String flowId) {
        return bundleService.listRevisionsByFlowId(flowId).stream()
                .map(FlowBundleMapper::toDefinition)
                .filter(Objects::nonNull)
                .toList();
    }

    @Override
    public List<CompiledFlowDefinition> list() {
        return bundleService.getAllActiveFlow().stream()
                .map(FlowBundleMapper::toDefinition)
                .filter(Objects::nonNull)
                .sorted(BY_FLOW_NAME)
                .toList();
    }

    @Override
    public List<CompiledFlowDefinition> listTriggerCandidates(String sourceModel) {
        List<CompiledFlowDefinition> shared = tenantFlows(SHARED_TENANT_ID).candidates(sourceModel);
        Long tenantId = ContextHolder.getContext().getTenantId();
        if (tenantId == null || SHARED_TENANT_ID.equals(tenantId)) {
            return shared;
        }
        List<CompiledFlowDefinition> own = tenantFlows(tenantId).candidates(sourceModel);
        if (own.isEmpty() || shared.isEmpty()) {
            return own.isEmpty() ? shared : own;
        }
        // Disjoint under tenant isolation; a single-tenant deployment reads every bundle into both.
        Map<Long, CompiledFlowDefinition> merged = new LinkedHashMap<>();
        own.forEach(definition -> merged.put(definition.getBundleId(), definition));
        shared.forEach(definition -> merged.putIfAbsent(definition.getBundleId(), definition));
        return List.copyOf(merged.values());
    }

    @Override
    public void refreshActiveForFlowId(String flowId) {
        invalidateTriggerIndex();
    }

    @Override
    public void evict(Long bundleId) {
        definitions.remove(bundleId);
    }

    private TenantFlows tenantFlows(Long tenantId) {
        TenantFlows cached = triggerIndex.get(tenantId);
        if (cached != null && !cached.isExpired(triggerCacheTtlNanos)) {
            return cached;
        }
        // One load per tenant at a time: concurrent callers wait for it rather than query too.
        TenantFlows loaded = triggerIndex.compute(tenantId, (id, current) ->
                current != null && !current.isExpired(triggerCacheTtlNanos) ? current : loadTenantFlows(id));
        // Tenants that went quiet do not keep their bundles in memory.
        triggerIndex.values().removeIf(entry -> entry.isExpired(triggerCacheTtlNanos));
        return loaded;
    }

    private TenantFlows loadTenantFlows(Long tenantId) {
        // Pinned to the tenant and permission-skipped: what can fire must not depend on whose
        // write happened to cause the load. Active bundles are never debug runs.
        List<CompiledFlowDefinition> active = ContextUtils.inTenantContext(tenantId, bundleService::getAllActiveFlow)
                .stream()
                .map(FlowBundleMapper::toDefinition)
                .filter(Objects::nonNull)
                .sorted(BY_FLOW_NAME)
                .toList();
        active.forEach(definition -> definitions.putIfAbsent(definition.getBundleId(), definition));
        log.debug("Loaded {} active flow bundle(s) of tenant {} for trigger matching", active.size(), tenantId);
        return new TenantFlows(active.stream().collect(Collectors.groupingBy(
                OrmFlowBundleRegistry::watchedModel, Collectors.toUnmodifiableList())), System.nanoTime());
    }

    /**
     * Drops the trigger-index entry of the tenant the current context writes to (all entries when
     * that is not one tenant), so this instance fires the new active set from the next event on.
     */
    private void invalidateTriggerIndex() {
        Context context = ContextHolder.getContext();
        if (context.isCrossTenant() || context.getTenantId() == null) {
            triggerIndex.clear();
        } else {
            triggerIndex.remove(context.getTenantId());
        }
    }

    /** Own-tenant and platform-shared bundles are visible; a cross-tenant context sees all. */
    private static boolean isVisible(CompiledFlowDefinition definition) {
        Context context = ContextHolder.getContext();
        Long owner = definition.getTenantId();
        return context.isCrossTenant()
                || SHARED_TENANT_ID.equals(owner)
                || Objects.equals(owner, context.getTenantId());
    }

    /** The model whose records the flow starts on; a flow started on no record is a candidate for no event. */
    private static String watchedModel(CompiledFlowDefinition definition) {
        boolean onRecord = definition.getTrigger() != null && definition.getTrigger().onRecord();
        return onRecord && StringUtils.hasText(definition.getModelName()) ? definition.getModelName() : NO_RECORD;
    }

    /** One tenant's active bundles, keyed by the model their trigger watches. */
    private record TenantFlows(Map<String, List<CompiledFlowDefinition>> byModel, long loadedAt) {

        boolean isExpired(long ttlNanos) {
            return System.nanoTime() - loadedAt >= ttlNanos;
        }

        /** Bundles started on records of {@code sourceModel}. */
        List<CompiledFlowDefinition> candidates(String sourceModel) {
            return StringUtils.hasText(sourceModel) ? byModel.getOrDefault(sourceModel, List.of()) : List.of();
        }
    }
}
