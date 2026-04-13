package io.softa.starter.studio.release.service.impl;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.context.annotation.Lazy;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import tools.jackson.core.type.TypeReference;

import io.softa.framework.base.context.ContextHolder;
import io.softa.framework.base.exception.IllegalArgumentException;
import io.softa.framework.base.security.EncryptUtils;
import io.softa.framework.base.utils.Assert;
import io.softa.framework.base.utils.DateUtils;
import io.softa.framework.base.utils.JsonUtils;
import io.softa.framework.orm.domain.Filters;
import io.softa.framework.orm.domain.FlexQuery;
import io.softa.framework.orm.enums.DatabaseType;
import io.softa.framework.orm.service.impl.EntityServiceImpl;
import io.softa.framework.web.dto.MetadataUpgradePackage;
import io.softa.starter.studio.release.dto.ModelChangesDTO;
import io.softa.starter.studio.release.entity.*;
import io.softa.starter.studio.release.enums.DesignAppEnvType;
import io.softa.starter.studio.release.enums.DesignAppVersionStatus;
import io.softa.starter.studio.release.enums.DesignDeploymentStatus;
import io.softa.starter.studio.release.enums.DesignWorkItemStatus;
import io.softa.starter.studio.release.event.DesignDeploymentSnapshotEvent;
import io.softa.starter.studio.release.service.*;
import io.softa.starter.studio.release.upgrade.DeploymentExecutor;
import io.softa.starter.studio.release.version.VersionDdl;
import io.softa.starter.studio.release.version.VersionDdlResult;
import io.softa.starter.studio.release.version.VersionMerger;

/**
 * DesignDeployment Model Service Implementation.
 * <p>
 * The Deployment is the single immutable deployment artifact. It combines content preparation
 * (released-version merging, DDL generation) and execution tracking into one self-contained record.
 * <p>
 * After the caller validates the target Version and Environment, the deployment process:
 * <ol>
 *   <li>Selects released versions in the sealedTime interval (env.currentVersionId, targetVersionId]</li>
 *   <li>Merges version contents and generates DDL</li>
 *   <li>Creates a Deployment record with all content + DDL</li>
 *   <li>Executes the deployment (sync or async)</li>
 *   <li>Updates env.currentVersionId on success</li>
 *   <li>Auto-freezes the target version after successful PROD deployment</li>
 * </ol>
 */
@Service
public class DesignDeploymentServiceImpl extends EntityServiceImpl<DesignDeployment, Long>
        implements DesignDeploymentService {

    @Autowired
    private DeploymentExecutor deploymentExecutor;

    @Lazy
    @Autowired
    private DesignAppEnvService appEnvService;

    @Lazy
    @Autowired
    private DesignAppVersionService appVersionService;

    @Autowired
    private DesignDeploymentVersionService deploymentVersionService;

    @Lazy
    @Autowired
    private DesignWorkItemService workItemService;

    @Autowired
    private DesignAppService appService;

    @Autowired
    private VersionDdl versionDdl;

    @Autowired
    private ApplicationEventPublisher applicationEventPublisher;

    /**
     * Deploy a validated Version to an Env.
     * <p>
     * This is the main entry point. It:
     * 1. Merges released version content and generates DDL
     * 2. Creates a self-contained Deployment record
     * 3. Executes the deployment
     * 4. Updates env.currentVersionId on success
     */
    @Override
    @Transactional(rollbackFor = Exception.class)
    public Long deployToEnv(DesignAppVersion targetVersion, DesignAppEnv targetEnv) {
        Long sourceVersionId = targetEnv.getCurrentVersionId();
        PreparedDeploymentArtifact artifact = prepareDeploymentArtifact(targetEnv, targetVersion, sourceVersionId);
        DesignDeployment deployment = createDeploymentRecord(targetEnv, artifact);
        recordIncludedVersions(deployment.getId(), artifact.versionsForMerge());

        // Execute deployment
        this.executeDeployment(deployment, targetEnv, targetVersion.getId());
        return deployment.getId();
    }


    /**
     * Retry a failed deployment by creating a new Deployment with the same parameters.
     */
    @Override
    @Transactional(rollbackFor = Exception.class)
    public Long retryDeployment(Long deploymentId) {
        DesignDeployment failedDeployment = this.getById(deploymentId)
                .orElseThrow(() -> new IllegalArgumentException("Deployment does not exist! {0}", deploymentId));
        Assert.isEqual(failedDeployment.getDeployStatus(), DesignDeploymentStatus.FAILURE,
                "Only FAILURE deployments can be retried! Current status: {0}", failedDeployment.getDeployStatus());

        return appVersionService.deployToEnv(
                failedDeployment.getTargetVersionId(),
                failedDeployment.getEnvId());
    }

    // ======================== Private methods ========================

    /**
     * Execute the actual deployment logic.
     */
    private void executeDeployment(DesignDeployment deployment, DesignAppEnv targetEnv, Long targetVersionId) {
        deployment.setDeployStatus(DesignDeploymentStatus.DEPLOYING);
        this.updateOne(deployment);

        long startNanos = System.nanoTime();
        try {
            List<ModelChangesDTO> mergedChanges = JsonUtils.jsonNodeToObject(
                    deployment.getMergedContent(), new TypeReference<>() {});

            // Convert to upgrade packages
            List<MetadataUpgradePackage> upgradePackages = deploymentExecutor.convertToUpgradePackages(mergedChanges);

            if (Boolean.TRUE.equals(targetEnv.getAsyncUpgrade())) {
                deploymentExecutor.asyncUpgrade(targetEnv, upgradePackages);
            } else {
                deploymentExecutor.syncUpgrade(targetEnv, upgradePackages);
            }

            // Update deployment status to SUCCESS
            deployment.setDeployStatus(DesignDeploymentStatus.SUCCESS);
            deployment.setDeployDuration(DateUtils.elapsedSeconds(startNanos));
            deployment.setFinishedTime(LocalDateTime.now());
            this.updateOne(deployment);

            // Update env.currentVersionId to the deployed version
            targetEnv.setCurrentVersionId(targetVersionId);
            appEnvService.updateOne(targetEnv);

            // Auto-freeze the deployed target version after successful PROD deployment.
            if (targetEnv.getEnvType() == DesignAppEnvType.PROD) {
                this.closeReleasedWorkItems(deployment);
                this.freezeTargetVersionIfNeeded(targetVersionId);
            }

            applicationEventPublisher.publishEvent(
                    new DesignDeploymentSnapshotEvent(targetEnv.getId(), deployment.getId(), List.copyOf(mergedChanges)));
        } catch (RuntimeException e) {
            try {
                deployment.setDeployStatus(DesignDeploymentStatus.FAILURE);
                deployment.setDeployDuration(DateUtils.elapsedSeconds(startNanos));
                deployment.setFinishedTime(LocalDateTime.now());
                deployment.setErrorMessage(e.getMessage());
                this.updateOne(deployment);
            } catch (RuntimeException updateException) {
                e.addSuppressed(updateException);
            }
            throw e;
        }
    }

    private void closeReleasedWorkItems(DesignDeployment deployment) {
        Filters versionFilters = new Filters().eq(DesignDeploymentVersion::getDeploymentId, deployment.getId());
        List<DesignDeploymentVersion> deploymentVersions = deploymentVersionService.searchList(new FlexQuery(versionFilters));
        if (deploymentVersions.isEmpty()) {
            return;
        }

        List<Long> versionIds = deploymentVersions.stream()
                .map(DesignDeploymentVersion::getVersionId)
                .distinct()
                .toList();
        Filters workItemFilters = new Filters().in(DesignWorkItem::getVersionId, versionIds);
        List<DesignWorkItem> workItems = workItemService.searchList(new FlexQuery(workItemFilters));
        if (workItems.isEmpty()) {
            return;
        }

        LocalDateTime closedTime = deployment.getFinishedTime() != null ? deployment.getFinishedTime() : LocalDateTime.now();
        for (DesignWorkItem workItem : workItems) {
            if (workItem.getStatus() == DesignWorkItemStatus.CLOSED && workItem.getClosedTime() != null) {
                continue;
            }
            if (workItem.getStatus() != DesignWorkItemStatus.DONE
                    && workItem.getStatus() != DesignWorkItemStatus.CLOSED) {
                continue;
            }
            workItem.setStatus(DesignWorkItemStatus.CLOSED);
            workItem.setClosedTime(closedTime);
            workItemService.updateOne(workItem);
        }
    }

    /**
     * Auto-freeze the deployed target version.
     */
    private void freezeTargetVersionIfNeeded(Long targetVersionId) {
        DesignAppVersion targetVersion = appVersionService.getById(targetVersionId)
                .orElseThrow(() -> new IllegalArgumentException("Version does not exist! {0}", targetVersionId));
        if (targetVersion.getStatus() == DesignAppVersionStatus.FROZEN) {
            return;
        }
        if (targetVersion.getStatus() == DesignAppVersionStatus.SEALED) {
            appVersionService.freezeVersion(targetVersionId);
        }
    }


    /**
     * Deserialize each version's versionedContent and merge them using {@link VersionMerger}.
     */
    private List<ModelChangesDTO> mergeVersionContents(List<DesignAppVersion> orderedVersions) {
        List<List<ModelChangesDTO>> allVersionChanges = new ArrayList<>();
        for (DesignAppVersion version : orderedVersions) {
            if (version.getVersionedContent() != null) {
                List<ModelChangesDTO> versionChanges = JsonUtils.jsonNodeToObject(
                        version.getVersionedContent(), new TypeReference<>() {});
                allVersionChanges.add(versionChanges);
            }
        }
        return VersionMerger.merge(allVersionChanges);
    }

    private PreparedDeploymentArtifact prepareDeploymentArtifact(
            DesignAppEnv targetEnv, DesignAppVersion targetVersion, Long sourceVersionId) {
        List<DesignAppVersion> versionsForMerge = appVersionService.getVersionsForMerge(sourceVersionId, targetVersion.getId());
        List<ModelChangesDTO> mergedChanges = mergeVersionContents(versionsForMerge);
        DatabaseType databaseType = appService.getFieldValue(targetEnv.getAppId(), DesignApp::getDatabaseType);
        VersionDdlResult ddlResult = versionDdl.generateDdlResult(databaseType, mergedChanges);
        String contentJson = JsonUtils.objectToString(mergedChanges);
        String diffHash = EncryptUtils.computeSha256(contentJson);
        String deployName = String.format("%s → %s (%s)",
                sourceVersionId != null ? "v" + sourceVersionId : "initial",
                targetVersion.getName(),
                targetEnv.getName());
        return new PreparedDeploymentArtifact(
                sourceVersionId,
                targetVersion.getId(),
                deployName,
                mergedChanges,
                ddlResult,
                diffHash,
                versionsForMerge
        );
    }

    private DesignDeployment createDeploymentRecord(DesignAppEnv targetEnv, PreparedDeploymentArtifact artifact) {
        DesignDeployment deployment = new DesignDeployment();
        deployment.setAppId(targetEnv.getAppId());
        deployment.setEnvId(targetEnv.getId());
        deployment.setSourceVersionId(artifact.sourceVersionId());
        deployment.setTargetVersionId(artifact.targetVersionId());
        deployment.setName(artifact.deployName());
        deployment.setDeployStatus(DesignDeploymentStatus.PENDING);
        deployment.setDiffHash(artifact.diffHash());
        deployment.setMergedContent(JsonUtils.objectToJsonNode(artifact.mergedChanges()));
        deployment.setMergedDdlTable(artifact.ddlResult().tableDdl());
        deployment.setMergedDdlIndex(artifact.ddlResult().indexDdl());
        deployment.setStartedTime(LocalDateTime.now());
        if (ContextHolder.getContext().getUserId() != null) {
            deployment.setOperatorId(String.valueOf(ContextHolder.getContext().getUserId()));
        }
        return this.createOneAndFetch(deployment);
    }

    private void recordIncludedVersions(Long deploymentId, List<DesignAppVersion> versionsForMerge) {
        int sequence = 1;
        for (DesignAppVersion version : versionsForMerge) {
            DesignDeploymentVersion dv = new DesignDeploymentVersion();
            dv.setDeploymentId(deploymentId);
            dv.setVersionId(version.getId());
            dv.setSequence(sequence++);
            deploymentVersionService.createOne(dv);
        }
    }

    private record PreparedDeploymentArtifact(
            Long sourceVersionId,
            Long targetVersionId,
            String deployName,
            List<ModelChangesDTO> mergedChanges,
            VersionDdlResult ddlResult,
            String diffHash,
            List<DesignAppVersion> versionsForMerge
    ) {}

}
