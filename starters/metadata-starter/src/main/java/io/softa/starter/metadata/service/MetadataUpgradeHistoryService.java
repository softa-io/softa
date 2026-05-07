package io.softa.starter.metadata.service;

import tools.jackson.databind.JsonNode;

import io.softa.framework.orm.service.EntityService;
import io.softa.starter.metadata.entity.MetadataUpgradeHistory;

/**
 * MetadataUpgradeHistory Service — owns the persistent state machine for every
 * upgrade dispatched to this runtime.
 * <p>
 * Each {@code mark*} method must commit independently of the caller's transaction so
 * the history row survives both upgrade success and upgrade failure. Implementations
 * use {@code Propagation.REQUIRES_NEW}.
 */
public interface MetadataUpgradeHistoryService extends EntityService<MetadataUpgradeHistory, Long> {

    /**
     * Insert a {@code RUNNING} row for the given callback token. The token has a UNIQUE
     * constraint, so a duplicate dispatch (studio retried because the previous attempt
     * timed out) is silently ignored on this side.
     *
     * @return {@code true} when this call inserted the row (first dispatch);
     *         {@code false} when a row with the same token already existed (replay).
     */
    boolean markRunning(String callbackToken, Long envId, JsonNode packageSummary);

    /**
     * Transition the row identified by {@code callbackToken} to {@code SUCCESS}.
     * Must be called only after {@link #markRunning} returned {@code true}.
     */
    void markSuccess(String callbackToken, double durationSeconds);

    /**
     * Transition the row identified by {@code callbackToken} to {@code FAILURE} and
     * record the first-line error message.
     */
    void markFailure(String callbackToken, String errorMessage, double durationSeconds);

    /**
     * Append a reload-broadcast failure warning to a history row that is already in
     * {@code SUCCESS} state. Used when the upgrade transaction committed but the
     * post-upgrade metadata reload broadcast failed — replicas may serve stale data
     * until a later reload trigger lands. The row stays {@code SUCCESS} (the data is
     * in); operators can audit reload incidents via the appended message.
     * <p>
     * No-ops (with a log warning) when the row is missing or not in {@code SUCCESS}
     * state — this method must not overwrite a {@code FAILURE} or {@code RUNNING}
     * row's error message.
     */
    void recordReloadWarning(String callbackToken, String warningMessage);

    /**
     * Look up a history row by its callback token. Returns {@code null} when no
     * upgrade was ever dispatched with that token.
     */
    MetadataUpgradeHistory findByToken(String callbackToken);
}
