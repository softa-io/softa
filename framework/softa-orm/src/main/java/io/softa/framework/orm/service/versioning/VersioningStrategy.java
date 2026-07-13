package io.softa.framework.orm.service.versioning;

import java.io.Serializable;
import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.Set;

import io.softa.framework.base.exception.IllegalArgumentException;
import io.softa.framework.orm.domain.Filters;
import io.softa.framework.orm.domain.FlexQuery;
import io.softa.framework.orm.entity.TimelineSlice;
import io.softa.framework.orm.enums.ConvertType;

/**
 * Per-model versioning seam: every write and read-scoping step in {@code ModelServiceImpl}
 * calls this strategy unconditionally, so timeline handling is polymorphic instead of an
 * {@code if (isTimelineModel)} branch at each call site. Non-timeline models resolve to the
 * no-op {@link IdentityStrategy}; timeline models resolve to {@link TimelineStrategy}, which
 * delegates to the interval-maintenance algorithm. Resolve instances via
 * {@link VersioningStrategyResolver}.
 *
 * <p>Deliberately NOT routed through this seam: the entity-delete prefetch in
 * {@code ModelServiceImpl.deleteByIds} reads all slices of a logical id via
 * {@code jdbcService.selectByIds} (unclamped) — routing it through {@link #fetchByIds} would
 * clamp it to the context effective date and silently skip past-only entities.
 */
public interface VersioningStrategy {

    /**
     * Create rows: plain insert for identity models; slice creation (first slice, or
     * split/correct against the neighbors) for timeline models.
     *
     * @param modelName model name
     * @param rows rows to create
     * @return the created rows carrying their generated primary keys
     */
    List<Map<String, Object>> create(String modelName, List<Map<String, Object>> rows);

    /**
     * Update rows previously validated by the caller: plain update for identity models;
     * slice update with neighbor end-date correction for timeline models.
     *
     * @param modelName model name
     * @param rows rows to update (timeline rows are keyed by {@code sliceId})
     * @param toUpdateFields union of updatable fields present on the rows
     * @return the number of rows updated
     */
    Integer update(String modelName, List<Map<String, Object>> rows, Set<String> toUpdateFields);

    /**
     * Resolve the logical ids the permission check must run against for an update batch.
     * Identity models: the rows' own {@code id} values. Timeline models: the trusted
     * {@code sliceId -> id} mapping read from the database (the input id is not reliable),
     * with each row's {@code id} backfilled in place as a side effect.
     *
     * @param modelName model name
     * @param rows the update batch (already pk-normalized by the caller)
     * @return logical ids to permission-check, aligned with the batch
     */
    Collection<Serializable> resolveTargetIds(String modelName, List<Map<String, Object>> rows);

    /**
     * Read rows by logical ids for the regular (non-delete) read path. Identity models read
     * by primary key; timeline models read the slice effective at the context date.
     *
     * @param modelName model name
     * @param ids logical ids
     * @param fields fields to read
     * @param convertType conversion applied to the result
     * @return matching rows
     */
    List<Map<String, Object>> fetchByIds(String modelName, List<? extends Serializable> ids,
                                         List<String> fields, ConvertType convertType);

    /**
     * Scope a Filters-shaped read: identity models pass through untouched; timeline models
     * get the effective-date clamp unless the caller already filters on the effective dates
     * (the dual-trigger across-timeline contract).
     *
     * @param modelName model name
     * @param filters original filters
     * @return scoped filters
     */
    Filters scopeRead(String modelName, Filters filters);

    /**
     * Scope a FlexQuery-shaped read; same contract as {@link #scopeRead(String, Filters)}
     * with {@code FlexQuery.isAcrossTimeline()} as the across trigger.
     *
     * @param modelName model name
     * @param flexQuery query whose filters are scoped
     * @return scoped filters
     */
    Filters scopeRead(String modelName, FlexQuery flexQuery);

    /**
     * Load one version row (slice) by its physical id, for version-level operations.
     * Identity models have no versions: rejected.
     *
     * @param modelName model name
     * @param versionId the version's physical primary key ({@code sliceId})
     * @return the version slice
     */
    default TimelineSlice versionSlice(String modelName, Serializable versionId) {
        throw new IllegalArgumentException(
                "Model {0} is not a timeline model, and cannot read a version slice.", modelName);
    }

    /**
     * Delete one version row (slice), healing the predecessor's effective end date; the
     * entity itself survives, so the inbound-FK delete strategy is not triggered. Identity
     * models have no versions: rejected.
     *
     * @param modelName model name
     * @param slice the slice to delete, as loaded by {@link #versionSlice}
     * @return true on success
     */
    default boolean deleteVersion(String modelName, TimelineSlice slice) {
        throw new IllegalArgumentException(
                "Model {0} is not a timeline model, and cannot delete slice.", modelName);
    }
}
