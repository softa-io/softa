package io.softa.starter.flow.runtime.bundle;

import java.time.LocalDateTime;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import jakarta.annotation.PostConstruct;
import lombok.extern.slf4j.Slf4j;
import org.springframework.context.annotation.Primary;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.stereotype.Component;

import io.softa.starter.flow.design.DesignFlowDefinition;
import io.softa.starter.flow.entity.FlowBundle;
import io.softa.starter.flow.runtime.exception.FlowRuntimeException;
import io.softa.starter.flow.service.FlowBundleService;

/**
 * ORM-backed {@link FlowBundleRegistry} with a {@code bundleId}-keyed in-memory cache.
 * <p>
 * A secondary {@code designId → bundleId} index enables O(1) "start latest" resolution
 * without any flowCode involvement.
 * </p>
 */
@Slf4j
@Primary
@Component
public class OrmFlowBundleRegistry implements FlowBundleRegistry {

    private static final int MAX_REVISION_RETRIES = 3;

    private final FlowBundleService bundleService;

    /** Primary cache: bundleId → CompiledFlowDefinition. */
    private final ConcurrentHashMap<Long, CompiledFlowDefinition> bundleCache = new ConcurrentHashMap<>();

    /** Secondary index: designId → bundleId of active revision. */
    private final ConcurrentHashMap<Long, Long> activeBundleIndex = new ConcurrentHashMap<>();

    public OrmFlowBundleRegistry(FlowBundleService bundleService) {
        this.bundleService = bundleService;
    }

    @PostConstruct
    void warmUpCache() {
        List<FlowBundle> allActive = bundleService.getAllActiveFlow();
        int loaded = 0;
        for (FlowBundle entity : allActive) {
            CompiledFlowDefinition definition = FlowBundleMapper.toDefinition(entity);
            if (definition != null && entity.getId() != null && entity.getDesignId() != null) {
                bundleCache.put(entity.getId(), definition);
                activeBundleIndex.put(entity.getDesignId(), entity.getId());
                loaded++;
            }
        }
        log.info("OrmFlowBundleRegistry: loaded {} active flow bundle(s) from database", loaded);
    }

    @Override
    public CompiledFlowDefinition register(CompiledFlowDefinition definition,
                                           DesignFlowDefinition design,
                                           Long designId) {
        return doRegister(definition, design, designId, false);
    }

    @Override
    public CompiledFlowDefinition registerDebug(CompiledFlowDefinition definition,
                                                DesignFlowDefinition design,
                                                Long designId) {
        return doRegister(definition, design, designId, true);
    }

    private CompiledFlowDefinition doRegister(CompiledFlowDefinition definition,
                                              DesignFlowDefinition design,
                                              Long designId,
                                              boolean debug) {
        if (definition == null) {
            throw new FlowRuntimeException("Compiled flow definition must not be null");
        }
        if (designId == null) {
            throw new FlowRuntimeException("designId is required to register a flow bundle");
        }

        // Revision allocation races with concurrent publishes of the same design: the
        // uk_tenant_design_revision unique index rejects the loser, which retries with
        // a freshly computed revision instead of silently overwriting the winner's row.
        for (int attempt = 1; attempt <= MAX_REVISION_RETRIES; attempt++) {
            int nextRevision = bundleService.getNextRevision(designId);

            // toBuilder keeps every compiled field (a hand-written rebuild silently
            // dropped newly added ones); only revision metadata is stamped here.
            CompiledFlowDefinition published = definition.toBuilder()
                    .revision(nextRevision)
                    .designId(designId)
                    .publishedAt(LocalDateTime.now())
                    .build();

            FlowBundle entity = FlowBundleMapper.toEntity(published, design, null, designId);
            if (debug) {
                // resolvable like any bundle, but never the design's effective revision
                entity.setActive(false);
                entity.setDebug(true);
            }
            try {
                bundleService.saveBundle(entity);
            } catch (DuplicateKeyException e) {
                log.info("Revision {} for design {} was claimed concurrently, retrying ({}/{})",
                        nextRevision, designId, attempt, MAX_REVISION_RETRIES);
                continue;
            }

            published.setBundleId(entity.getId());
            bundleCache.put(entity.getId(), published);
            if (!debug) {
                bundleService.markAsActive(designId, nextRevision);
                activeBundleIndex.put(designId, entity.getId());
            }

            log.debug("Registered flow bundle: bundleId={} designId={} r{} debug={}",
                    entity.getId(), designId, nextRevision, debug);
            return published;
        }
        throw new FlowRuntimeException(
                "Failed to allocate a publish revision for design " + designId + " after "
                        + MAX_REVISION_RETRIES + " attempts (concurrent publishes)");
    }

    @Override
    public Optional<CompiledFlowDefinition> getByBundleId(Long bundleId) {
        CompiledFlowDefinition cached = bundleCache.get(bundleId);
        if (cached != null) {
            return Optional.of(cached);
        }
        return bundleService.findById(bundleId)
                .map(FlowBundleMapper::toDefinition)
                .map(def -> {
                    bundleCache.put(bundleId, def);
                    return def;
                });
    }

    @Override
    public Optional<CompiledFlowDefinition> getActiveByDesignId(Long designId) {
        Long bundleId = activeBundleIndex.get(designId);
        if (bundleId != null) {
            return getByBundleId(bundleId);
        }
        return bundleService.findActiveByDesignId(designId)
                .map(entity -> {
                    CompiledFlowDefinition def = FlowBundleMapper.toDefinition(entity);
                    if (def != null && entity.getId() != null) {
                        bundleCache.put(entity.getId(), def);
                        activeBundleIndex.put(designId, entity.getId());
                    }
                    return def;
                });
    }

    @Override
    public List<CompiledFlowDefinition> listRevisionsByDesignId(Long designId) {
        return bundleService.listRevisionsByDesignId(designId).stream()
                .map(entity -> {
                    CompiledFlowDefinition def = FlowBundleMapper.toDefinition(entity);
                    if (def != null && entity.getId() != null) {
                        bundleCache.put(entity.getId(), def);
                    }
                    return def;
                })
                .filter(Objects::nonNull)
                .toList();
    }

    @Override
    public Collection<CompiledFlowDefinition> list() {
        if (bundleCache.isEmpty()) {
            warmUpCache();
        }
        return activeBundleIndex.values().stream()
                .map(bundleCache::get)
                .filter(Objects::nonNull)
                .sorted(Comparator.comparing(CompiledFlowDefinition::getFlowCode,
                        Comparator.nullsLast(Comparator.naturalOrder())))
                .toList();
    }

    @Override
    public void evict(Long bundleId) {
        bundleCache.remove(bundleId);
    }

    @Override
    public void refreshActiveForDesignId(Long designId) {
        activeBundleIndex.remove(designId);
        bundleService.findActiveByDesignId(designId).ifPresent(entity -> {
            CompiledFlowDefinition def = FlowBundleMapper.toDefinition(entity);
            if (def != null && entity.getId() != null) {
                bundleCache.put(entity.getId(), def);
                activeBundleIndex.put(designId, entity.getId());
            }
        });
    }
}
