package io.softa.starter.studio.release.service;

import io.softa.framework.orm.service.EntityService;
import io.softa.starter.metadata.dto.MetadataUpgradeCallback;
import io.softa.starter.studio.release.entity.DesignAppEnv;
import io.softa.starter.studio.release.entity.DesignAppVersion;
import io.softa.starter.studio.release.entity.DesignDeployment;

/**
 * DesignDeployment Model Service Interface.
 * <p>
 * A Deployment is the immutable deployment artifact produced when a Version is deployed to an Env.
 * It combines what was previously split between Release (content preparation) and Deployment (execution)
 * into a single self-contained record.
 * <p>
 * After the caller validates the target Version and Environment, the deployment process:
 * <ol>
 *   <li>Selects released versions in the sealedTime interval from env.currentVersionId to targetVersionId</li>
 *   <li>Merges version contents and generates DDL</li>
 *   <li>Creates a Deployment record with merged content, DDL, and callback coordinates</li>
 *   <li>Publishes an after-commit event that dispatches the upgrade on a virtual thread</li>
 *   <li>Advances env.currentVersionId after the upgrade reports SUCCESS</li>
 *   <li>Auto-freezes the target version after a successful PROD deployment</li>
 * </ol>
 * <p>
 * Every deployment runs async from the HTTP caller's perspective — the deploy endpoint
 * returns as soon as the Deployment record is committed, clients poll
 * {@link DesignDeployment#getDeployStatus()} for completion. Completion always
 * arrives via the webhook callback posted by the target runtime to
 * {@link #handleUpgradeCallback}.
 */
public interface DesignDeploymentService extends EntityService<DesignDeployment, Long> {

    /**
     * Deploy a validated Version to an Env.
     * <p>
     * Transitions the env mutex {@code STABLE} → {@code DEPLOYING}, creates a
     * {@code PENDING} Deployment, mints a one-time callback token, then publishes
     * {@code AsyncDeploymentTriggerEvent} so the actual upgrade runs after this
     * transaction commits. The caller gets the deployment id back immediately.
     * <p>
     * Final status is reached later when the runtime webhook lands at
     * {@link #handleUpgradeCallback}.
     * {@code env.currentVersionId} only advances on {@code SUCCESS}.
     *
     * @param targetVersion validated target version
     * @param targetEnv validated target environment
     * @return Deployment record ID
     */
    Long deployToEnv(DesignAppVersion targetVersion, DesignAppEnv targetEnv);

    /**
     * Retry a failed deployment by creating a new Deployment with the same content.
     *
     * @param deploymentId Deployment ID
     * @return New deployment record ID
     */
    Long retryDeployment(Long deploymentId);

    /**
     * Cancel a stuck deployment and release its env mutex.
     * <p>
     * Allowed transitions:
     * <ul>
     *   <li>{@code PENDING} → {@code ROLLED_BACK}: the async trigger never fired</li>
     *   <li>{@code DEPLOYING} → {@code ROLLED_BACK}: the worker is stuck, or a remote
     *       runtime never sent its callback, or the JVM died before status transition</li>
     * </ul>
     * <p>
     * <b>No automatic rollback is performed.</b> This API only marks the deployment
     * record as {@code ROLLED_BACK} and flips the env back to {@code STABLE} so new
     * deployments can proceed — any DDL or data changes that the upgrade had already
     * applied on the runtime side stay applied. Operators must manually revert
     * runtime state when partial changes were committed.
     *
     * @param deploymentId Deployment ID
     */
    void cancelDeployment(Long deploymentId);

    /**
     * Entry point invoked by {@code AsyncDeploymentListener} after the deploy-transaction
     * commits. Loads the deployment + env fresh (post-commit visibility), then posts
     * the signed envelope to the runtime's upgrade endpoint. The runtime responds 202,
     * applies the upgrade on its own virtual thread, and posts completion back to
     * {@link #handleUpgradeCallback}. The env lock is held until that callback lands
     * — or until {@link #cancelDeployment} is called manually.
     * <p>
     * Not intended for external callers — clients go through {@link #deployToEnv}.
     *
     * @param deploymentId    deployment to execute
     * @param envId           env holding the mutex
     * @param targetVersionId version to promote {@code env.currentVersionId} to on success
     */
    void executeAsyncDeployment(Long deploymentId, Long envId, Long targetVersionId);

    /**
     * Apply a runtime → studio completion webhook to the matching deployment.
     * <p>
     * Invoked by the callback controller after verifying that the supplied
     * {@code callbackToken} matches a pending deployment and has not expired.
     * Transitions the deployment to {@code SUCCESS} / {@code FAILURE}, advances
     * {@code env.currentVersionId} on success, releases the env lock, and triggers
     * the snapshot + version-freeze side effects on success.
     * <p>
     * Safe against duplicate deliveries — the token is marked burned on first
     * receipt and subsequent callbacks with the same token are rejected by the
     * controller before this method runs.
     *
     * @param callbackToken the one-time token from the originating envelope
     * @param payload       the completion status + optional error detail
     */
    void handleUpgradeCallback(String callbackToken, MetadataUpgradeCallback payload);

}
