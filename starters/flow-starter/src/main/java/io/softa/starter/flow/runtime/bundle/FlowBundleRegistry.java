package io.softa.starter.flow.runtime.bundle;

import java.util.Collection;
import java.util.List;
import java.util.Optional;

import io.softa.starter.flow.design.DesignFlowDefinition;

/**
 * Runtime registry for published compiled flow bundles.
 * <p>
 * All lookups are keyed on {@code bundleId} (exact revision) or {@code flowId}
 * (stable flow identity).
 * </p>
 * <p>
 * Every lookup answers for the current context: a bundle of another tenant reads as absent,
 * platform-shared bundles ({@link #SHARED_TENANT_ID}) are visible to every tenant, and a
 * cross-tenant context sees all of them.
 * </p>
 */
public interface FlowBundleRegistry {

    /** Tenant id of platform-shared bundles: they apply to every tenant. */
    Long SHARED_TENANT_ID = 0L;

    /**
     * Persist a new revision and make it the design's active one.
     *
     * @param definition compiled definition
     * @param design     original design document (stored for editor restore; may be null)
     * @param flowId   the flow id (required for persistent registries; may be null in tests)
     */
    CompiledFlowDefinition register(CompiledFlowDefinition definition,
                                    DesignFlowDefinition design,
                                    String flowId);

    /** Convenience: publish without a design document. */
    default CompiledFlowDefinition register(CompiledFlowDefinition definition) {
        return register(definition, null, null);
    }

    /**
     * Persist a debug-run bundle: resolvable by {@code bundleId} like any
     * revision (so instances survive restarts), but never active and hidden from
     * revision lists. Purged by the maintenance job after the retention window.
     */
    default CompiledFlowDefinition registerDebug(CompiledFlowDefinition definition,
                                                 DesignFlowDefinition design,
                                                 String flowId) {
        throw new UnsupportedOperationException("Debug bundles are not supported by this registry");
    }

    /** Look up an exact revision by bundle id (primary runtime key). */
    Optional<CompiledFlowDefinition> getByBundleId(Long bundleId);

    /** Look up the active (currently effective) revision for a flow id. */
    /**
     * The revision new instances of the flow start from: its active revision while the flow is enabled; none
     * while it is disabled or was never published. A running instance resolves its own revision by bundle id.
     */
    Optional<CompiledFlowDefinition> getActiveByFlowId(String flowId);

    /** List all revisions for a flow id, ordered by revision descending. */
    List<CompiledFlowDefinition> listRevisionsByFlowId(String flowId);

    /** The current tenant's active revisions of its enabled flows (one per flow), ordered by flow name. */
    Collection<CompiledFlowDefinition> list();

    /**
     * The active revisions of the enabled flows started on records of {@code sourceModel} — the flows whose trigger
     * names it — in the current context: the current tenant's and the platform-shared ones, only the shared ones when the
     * context carries no tenant. Callers still apply the full trigger match, tenant check included.
     * <p>The default offers everything {@link #list()} returns.</p>
     */
    default List<CompiledFlowDefinition> listTriggerCandidates(String sourceModel) {
        return List.copyOf(list());
    }

    /**
     * The active revision of a design changed (rollback / roll-forward): make the new one
     * effective. Default no-op; caching implementations should override.
     */
    default void refreshActiveForFlowId(String flowId) {
    }

    /**
     * Drop a bundle from the in-memory cache (used when its row is purged).
     * Default no-op; caching implementations should override.
     */
    default void evict(Long bundleId) {
    }
}
