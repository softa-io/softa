package io.softa.starter.studio.release.service;

import io.softa.framework.orm.service.EntityService;
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
 *   <li>Creates a Deployment record with merged content, DDL, and execution status</li>
 *   <li>Executes the deployment (sync or async)</li>
 *   <li>Updates env.currentVersionId on success</li>
 *   <li>Auto-freezes the target version after successful PROD deployment</li>
 * </ol>
 */
public interface DesignDeploymentService extends EntityService<DesignDeployment, Long> {

    /**
     * Deploy a validated Version to an Env.
     * <p>
     * This method assumes the caller has already validated the target Version and Environment. It automatically:
     * <ul>
 *   <li>Selects released versions in the sealedTime interval from env.currentVersionId to targetVersionId</li>
 *   <li>Merges version contents and generates DDL</li>
 *   <li>Creates a self-contained Deployment record</li>
     *   <li>Executes the deployment</li>
     *   <li>Updates env.currentVersionId to targetVersionId on success</li>
     *   <li>Auto-freezes the target version after successful PROD deployment</li>
     * </ul>
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

}
