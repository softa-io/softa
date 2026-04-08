package io.softa.starter.studio.release.service.impl;

import java.time.LocalDateTime;
import java.util.List;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.annotation.Lazy;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import io.softa.framework.base.exception.IllegalArgumentException;
import io.softa.framework.base.security.EncryptUtils;
import io.softa.framework.base.utils.Assert;
import io.softa.framework.base.utils.JsonUtils;
import io.softa.framework.orm.domain.Filters;
import io.softa.framework.orm.domain.FlexQuery;
import io.softa.framework.orm.domain.Orders;
import io.softa.framework.orm.enums.DatabaseType;
import io.softa.framework.orm.service.impl.EntityServiceImpl;
import io.softa.starter.studio.release.dto.ModelChangesDTO;
import io.softa.starter.studio.release.entity.*;
import io.softa.starter.studio.release.enums.DesignAppVersionStatus;
import io.softa.starter.studio.release.service.*;
import io.softa.starter.studio.release.version.VersionControl;
import io.softa.starter.studio.release.version.VersionDdl;

/**
 * DesignAppVersion Model Service Implementation
 */
@Service
public class DesignAppVersionServiceImpl extends EntityServiceImpl<DesignAppVersion, Long> implements DesignAppVersionService {

    @Autowired
    private DesignWorkItemService workItemService;


    @Autowired
    private VersionControl versionControl;

    @Autowired
    private VersionDdl versionDdl;

    @Autowired
    private DesignDeploymentVersionService deploymentVersionService;

    @Autowired
    private DesignAppService appService;

    @Autowired
    private DesignAppEnvService appEnvService;

    @Lazy
    @Autowired
    private DesignDeploymentService deploymentService;

    @Override
    @Transactional(rollbackFor = Exception.class)
    public Long deployToEnv(Long versionId, Long envId) {
        DesignAppVersion targetVersion = this.getById(versionId)
                .orElseThrow(() -> new IllegalArgumentException("Version does not exist! {0}", versionId));
        DesignAppEnv targetEnv = appEnvService.getById(envId)
                .orElseThrow(() -> new IllegalArgumentException("Environment does not exist! {0}", envId));

        Assert.isEqual(targetVersion.getAppId(), targetEnv.getAppId(),
                "Version and Environment must belong to the same App!");
        Assert.isTrue(targetVersion.getStatus() == DesignAppVersionStatus.SEALED
                        || targetVersion.getStatus() == DesignAppVersionStatus.FROZEN,
                "Only SEALED or FROZEN versions can be deployed! Current status: {0}", targetVersion.getStatus());

        return deploymentService.deployToEnv(targetVersion, targetEnv);
    }

    /**
     * Seal the version: aggregate changes from selected WorkItems, compute diffHash,
     * and transition status to SEALED (immutable).
     *
     * @param id Version ID
     */
    @Override
    @Transactional(rollbackFor = Exception.class)
    public void sealVersion(Long id) {
        DesignAppVersion appVersion = this.getById(id)
                .orElseThrow(() -> new IllegalArgumentException("The specified version does not exist! {0}", id));
        Assert.isEqual(appVersion.getStatus(), DesignAppVersionStatus.DRAFT,
                "Only DRAFT versions can be sealed! Current status: {0}", appVersion.getStatus());

        // Aggregate the latest changes from selected WorkItems
        this.fillAppVersionFields(appVersion);

        appVersion.setStatus(DesignAppVersionStatus.SEALED);
        appVersion.setSealedTime(LocalDateTime.now());
        this.updateOne(appVersion);
    }

    /**
     * Unseal a SEALED version back to DRAFT, clearing its versionedContent and diffHash.
     * <p>
     * Safety constraints:
     * <ul>
     *   <li>Only SEALED versions can be unsealed (FROZEN versions are immutable)</li>
     *   <li>No Release may reference this version (it must not have been deployed)</li>
     * </ul>
     *
     * @param id Version ID
     */
    @Override
    @Transactional(rollbackFor = Exception.class)
    public void unsealVersion(Long id) {
        DesignAppVersion appVersion = this.getById(id)
                .orElseThrow(() -> new IllegalArgumentException("Version does not exist! {0}", id));
        Assert.isEqual(appVersion.getStatus(), DesignAppVersionStatus.SEALED,
                "Only SEALED versions can be unsealed! Current status: {0}", appVersion.getStatus());

        // Check: no Deployment references this version (it must not have been deployed)
        Filters deployFilter = new Filters().eq(DesignDeploymentVersion::getVersionId, id);
        Assert.notTrue(deploymentVersionService.exist(deployFilter),
                "Cannot unseal: this version has already been deployed!");

        // Clear sealed data and revert to DRAFT
        appVersion.setStatus(DesignAppVersionStatus.DRAFT);
        appVersion.setVersionedContent(null);
        appVersion.setDiffHash(null);
        appVersion.setSealedTime(null);
        this.updateOne(appVersion, false);
    }

    /**
     * Freeze the version, marking that it has been deployed
     * and can no longer be changed.
     *
     * @param id Version ID
     */
    @Override
    @Transactional(rollbackFor = Exception.class)
    public void freezeVersion(Long id) {
        DesignAppVersion appVersion = this.getById(id)
                .orElseThrow(() -> new IllegalArgumentException("The specified version does not exist! {0}", id));
        Assert.isEqual(appVersion.getStatus(), DesignAppVersionStatus.SEALED,
                "Only SEALED versions can be frozen! Current status: {0}", appVersion.getStatus());
        appVersion.setStatus(DesignAppVersionStatus.FROZEN);
        appVersion.setFrozenTime(LocalDateTime.now());
        this.updateOne(appVersion);
    }

    /**
     * Preview the merged content of the version without modifying its status.
     *
     * @param id Version ID
     * @return list of model-level change summaries
     */
    @Override
    public List<ModelChangesDTO> previewVersion(Long id) {
        DesignAppVersion appVersion = this.getById(id)
                .orElseThrow(() -> new IllegalArgumentException("Version does not exist! {0}", id));
        Assert.notNull(appVersion.getAppId(), "Version {0} has no appId set!", id);

        List<Long> workItemIds = getSelectedWorkItemIds(id);
        Assert.notEmpty(workItemIds, "Version {0} has no WorkItems selected!", id);
        return versionControl.collectModelChanges(workItemIds);
    }

    /**
     * Preview the DDL SQL generated from the version's change data.
     * <p>
     * Version does not store DDL — it stores change data ({@code versionedContent}).
     * DDL is always generated on the fly from the change data.
     * <ul>
     *   <li>SEALED/FROZEN: change data is read from the stored {@code versionedContent} JSON field.</li>
     *   <li>DRAFT: change data is aggregated live from WorkItems via ES changelog.</li>
     * </ul>
     *
     * @param id Version ID
     * @return DDL SQL string
     */
    @Override
    public String previewVersionDDL(Long id) {
        DesignAppVersion appVersion = this.getById(id)
                .orElseThrow(() -> new IllegalArgumentException("Version does not exist! {0}", id));
        List<ModelChangesDTO> changes;
        if (appVersion.getVersionedContent() != null) {
            // SEALED or FROZEN — deserialize change data from stored versionedContent
            changes = JsonUtils.jsonNodeToObject(appVersion.getVersionedContent(),
                    new tools.jackson.core.type.TypeReference<>() {});
        } else {
            // DRAFT — aggregate live change data from WorkItems
            changes = previewVersion(id);
        }
        DatabaseType databaseType = appService.getFieldValue(appVersion.getAppId(), DesignApp::getDatabaseType);
        return versionDdl.generateDDL(databaseType, changes);
    }

    /**
     * Return the released versions that should be merged when deploying from
     * {@code fromVersionId} (exclusive) to {@code toVersionId} (inclusive).
     *
     * @param fromVersionId starting deployed version (exclusive), or null for initial deployment
     * @param toVersionId   target version (inclusive)
     * @return ordered list by sealedTime ascending
     */
    @Override
    public List<DesignAppVersion> getVersionsForMerge(Long fromVersionId, Long toVersionId) {
        Assert.notNull(toVersionId, "Target version ID is required.");

        DesignAppVersion targetVersion = getReleasedVersionForMerge(toVersionId, "Target");
        LocalDateTime lowerBound = null;
        if (fromVersionId != null) {
            DesignAppVersion currentVersion = getReleasedVersionForMerge(fromVersionId, "Current");
            Assert.isEqual(currentVersion.getAppId(), targetVersion.getAppId(),
                    "Current version and target version must belong to the same App!");
            Assert.isTrue(currentVersion.getSealedTime().isBefore(targetVersion.getSealedTime()),
                    "Target version must be newer than current version!");
            lowerBound = currentVersion.getSealedTime();
        }

        Filters filters = new Filters()
                .eq(DesignAppVersion::getAppId, targetVersion.getAppId())
                .isSet(DesignAppVersion::getSealedTime)
                .in(DesignAppVersion::getStatus, List.of(
                        DesignAppVersionStatus.SEALED,
                        DesignAppVersionStatus.FROZEN))
                .le(DesignAppVersion::getSealedTime, targetVersion.getSealedTime());
        if (lowerBound != null) {
            filters.gt(DesignAppVersion::getSealedTime, lowerBound);
        }

        FlexQuery query = new FlexQuery(filters);
        query.setOrders(Orders.ofAsc(DesignAppVersion::getSealedTime)
                .addAsc(DesignAppVersion::getCreatedTime)
                .addAsc(DesignAppVersion::getId));

        List<DesignAppVersion> versions = this.searchList(query);
        Assert.notEmpty(versions, "No deployable versions found from {0} to {1}!", fromVersionId, toVersionId);
        Assert.isEqual(versions.getLast().getId(), toVersionId,
                "Target version must be the latest released version in the merge interval!");
        return List.copyOf(versions);
    }

    private DesignAppVersion getReleasedVersionForMerge(Long versionId, String role) {
        DesignAppVersion version = this.getById(versionId)
                .orElseThrow(() -> new IllegalArgumentException("{0} version does not exist! {1}", role, versionId));
        Assert.isTrue(version.getStatus() == DesignAppVersionStatus.SEALED
                        || version.getStatus() == DesignAppVersionStatus.FROZEN,
                "{0} version must be SEALED or FROZEN! Current status: {1}", role, version.getStatus());
        Assert.notNull(version.getSealedTime(), "{0} version must have sealedTime! {1}", role, versionId);
        return version;
    }

    /**
     * Fill in the version fields by aggregating changes from all selected WorkItems.
     * Generates versionedContent (JSON) and diffHash.
     *
     * @param appVersion App version object (must already be persisted with an id)
     */
    private void fillAppVersionFields(DesignAppVersion appVersion) {
        Assert.notNull(appVersion.getAppId(), "Version {0} has no appId set!", appVersion.getId());
        List<Long> workItemIds = getSelectedWorkItemIds(appVersion.getId());
        Assert.notEmpty(workItemIds, "Version {0} has no WorkItems selected. Add WorkItems before sealing.",
                appVersion.getId());
        List<ModelChangesDTO> modelChangesDTOList = versionControl.collectModelChanges(workItemIds);

        String contentJson = JsonUtils.objectToString(modelChangesDTOList);
        String diffHash = EncryptUtils.computeSha256(contentJson);

        appVersion.setVersionedContent(JsonUtils.objectToJsonNode(modelChangesDTOList));
        appVersion.setDiffHash(diffHash);
    }

    /**
     * Get the list of WorkItem IDs associated with the version, ordered by WorkItem creation time ascending.
     * This ensures that newer WorkItems override older ones during merge.
     *
     * @param versionId Version ID
     * @return ordered list of WorkItem IDs (by creation time ascending)
     */
    private List<Long> getSelectedWorkItemIds(Long versionId) {
        Filters filters = new Filters().eq(DesignWorkItem::getVersionId, versionId);
        FlexQuery query = new FlexQuery(filters);
        List<DesignWorkItem> workItems = workItemService.searchList(query);
        if (workItems.isEmpty()) {
            return List.of();
        }
        return workItems.stream()
                .sorted((a, b) -> {
                    if (a.getCreatedTime() == null && b.getCreatedTime() == null) return 0;
                    if (a.getCreatedTime() == null) return -1;
                    if (b.getCreatedTime() == null) return 1;
                    return a.getCreatedTime().compareTo(b.getCreatedTime());
                })
                .map(DesignWorkItem::getId)
                .toList();
    }

}
