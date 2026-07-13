package io.softa.starter.flow.runtime.bundle;

import java.util.Collection;
import java.util.List;
import java.util.Optional;

import io.softa.starter.flow.design.DesignFlowDefinition;

/**
 * Runtime registry for published compiled flow bundles.
 * <p>
 * All lookups are keyed on {@code bundleId} (exact revision) or {@code designId}
 * (stable flow identity). {@code flowCode} is intentionally absent from the registry
 * API: it is not tenant-unique and cannot be used as a reliable lookup key.
 * </p>
 */
public interface FlowBundleRegistry {

    /**
     * Persist and cache a new revision.
     *
     * @param definition compiled definition
     * @param design     original design document (stored for editor restore; may be null)
     * @param designId   source design id (required for persistent registries; may be null in tests)
     */
    CompiledFlowDefinition register(CompiledFlowDefinition definition,
                                    DesignFlowDefinition design,
                                    Long designId);

    /** Convenience: publish without a design document. */
    default CompiledFlowDefinition register(CompiledFlowDefinition definition) {
        return register(definition, null, null);
    }

    /**
     * Persist and cache a debug-run bundle: resolvable by {@code bundleId} like any
     * revision (so instances survive restarts), but never active and hidden from
     * revision lists. Purged by the maintenance job after the retention window.
     */
    default CompiledFlowDefinition registerDebug(CompiledFlowDefinition definition,
                                                 DesignFlowDefinition design,
                                                 Long designId) {
        throw new UnsupportedOperationException("Debug bundles are not supported by this registry");
    }

    /** Look up an exact revision by bundle id (primary runtime key). */
    Optional<CompiledFlowDefinition> getByBundleId(Long bundleId);

    /** Look up the active (currently effective) revision for a design id. */
    Optional<CompiledFlowDefinition> getActiveByDesignId(Long designId);

    /** List all revisions for a design id, ordered by revision descending. */
    List<CompiledFlowDefinition> listRevisionsByDesignId(Long designId);

    /** All active bundles (one per flow design). */
    Collection<CompiledFlowDefinition> list();

    /**
     * Refresh the in-memory active-bundle pointer for a design after a rollback.
     * Default no-op; persistent implementations should override.
     */
    default void refreshActiveForDesignId(Long designId) {
    }

    /**
     * Drop a bundle from the in-memory cache (used when its row is purged).
     * Default no-op; caching implementations should override.
     */
    default void evict(Long bundleId) {
    }
}
