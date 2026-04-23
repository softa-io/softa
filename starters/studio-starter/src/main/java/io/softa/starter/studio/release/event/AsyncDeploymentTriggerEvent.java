package io.softa.starter.studio.release.event;

/**
 * Published by {@code DesignDeploymentServiceImpl.deployToEnv} once a deployment record
 * has been created. The {@code @TransactionalEventListener(AFTER_COMMIT)} in
 * {@link AsyncDeploymentListener} picks this up after the enclosing transaction
 * commits and runs the upgrade on a virtual-thread {@code @Async} pool.
 * <p>
 * Every deploy goes through this event — the caller never waits on the upgrade. Local
 * envs are applied inline by the listener; remote envs are dispatched over HTTP and
 * completed via the {@code handleUpgradeCallback} webhook.
 * <p>
 * Only identifiers are carried: the listener reloads the entities fresh so it always
 * sees the committed state.
 *
 * @param deploymentId     the deployment record to execute
 * @param envId            target env (also the holder of the deploy-mutex lock)
 * @param targetVersionId  the version being deployed
 */
public record AsyncDeploymentTriggerEvent(
        Long deploymentId,
        Long envId,
        Long targetVersionId
) {}
