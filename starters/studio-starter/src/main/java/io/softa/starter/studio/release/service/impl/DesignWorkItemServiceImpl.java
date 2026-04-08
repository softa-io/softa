package io.softa.starter.studio.release.service.impl;

import java.util.List;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.annotation.Lazy;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import io.softa.framework.base.exception.BusinessException;
import io.softa.framework.base.exception.IllegalArgumentException;
import io.softa.framework.base.utils.Assert;
import io.softa.framework.orm.service.impl.EntityServiceImpl;
import io.softa.starter.studio.release.dto.ModelChangesDTO;
import io.softa.starter.studio.release.entity.DesignApp;
import io.softa.starter.studio.release.entity.DesignAppVersion;
import io.softa.starter.studio.release.entity.DesignWorkItem;
import io.softa.starter.studio.release.enums.DesignAppVersionStatus;
import io.softa.starter.studio.release.enums.DesignWorkItemStatus;
import io.softa.starter.studio.release.service.DesignAppService;
import io.softa.starter.studio.release.service.DesignAppVersionService;
import io.softa.starter.studio.release.service.DesignWorkItemService;
import io.softa.starter.studio.release.version.VersionControl;
import io.softa.starter.studio.release.version.VersionDdl;

/**
 * DesignWorkItem Model Service Implementation
 */
@Service
public class DesignWorkItemServiceImpl extends EntityServiceImpl<DesignWorkItem, Long> implements DesignWorkItemService {

    @Autowired
    private VersionControl versionControl;

    @Autowired
    private VersionDdl versionDdl;

    @Autowired
    @Lazy
    private DesignAppVersionService appVersionService;

    @Autowired
    private DesignAppService appService;

    /**
     * Complete the WorkItem and transition status to DONE.
     * The WorkItem must be in IN_PROGRESS status.
     */
    @Override
    @Transactional(rollbackFor = Exception.class)
    public void doneWorkItem(Long id) {
        DesignWorkItem workItem = this.getById(id)
                .orElseThrow(() -> new IllegalArgumentException("WorkItem does not exist! {0}", id));
        Assert.isEqual(workItem.getStatus(), DesignWorkItemStatus.IN_PROGRESS,
                "Only IN_PROGRESS WorkItems can be done! Current status: {0}", workItem.getStatus());
        workItem.setStatus(DesignWorkItemStatus.DONE);
        this.updateOne(workItem);
    }

    /**
     * Cancel the WorkItem. Only allowed for IN_PROGRESS, DONE, or DEFERRED.
     */
    @Override
    @Transactional(rollbackFor = Exception.class)
    public void cancelWorkItem(Long id) {
        DesignWorkItem workItem = this.getById(id)
                .orElseThrow(() -> new IllegalArgumentException("WorkItem does not exist! {0}", id));
        Assert.isTrue(workItem.getStatus() == DesignWorkItemStatus.IN_PROGRESS
                        || workItem.getStatus() == DesignWorkItemStatus.DONE
                        || workItem.getStatus() == DesignWorkItemStatus.DEFERRED,
                "Only IN_PROGRESS, DONE, or DEFERRED WorkItems can be cancelled! Current status: {0}",
                workItem.getStatus());
        workItem.setStatus(DesignWorkItemStatus.CANCELLED);
        this.updateOne(workItem);
    }

    /**
     * Defer the WorkItem. Only allowed for IN_PROGRESS.
     */
    @Override
    @Transactional(rollbackFor = Exception.class)
    public void deferWorkItem(Long id) {
        DesignWorkItem workItem = this.getById(id)
                .orElseThrow(() -> new IllegalArgumentException("WorkItem does not exist! {0}", id));
        Assert.isEqual(workItem.getStatus(), DesignWorkItemStatus.IN_PROGRESS,
                "Only IN_PROGRESS WorkItems can be deferred! Current status: {0}", workItem.getStatus());
        workItem.setStatus(DesignWorkItemStatus.DEFERRED);
        this.updateOne(workItem);
    }

    /**
     * Reopen a DONE, CANCELLED, or DEFERRED WorkItem back to IN_PROGRESS.
     */
    @Override
    @Transactional(rollbackFor = Exception.class)
    public void reopenWorkItem(Long id) {
        DesignWorkItem workItem = this.getById(id)
                .orElseThrow(() -> new IllegalArgumentException("WorkItem does not exist! {0}", id));
        Assert.isTrue(workItem.getStatus() == DesignWorkItemStatus.DONE
                        || workItem.getStatus() == DesignWorkItemStatus.CANCELLED
                        || workItem.getStatus() == DesignWorkItemStatus.DEFERRED,
                "Only DONE, CANCELLED, or DEFERRED WorkItems can be reopened! Current status: {0}",
                workItem.getStatus());
        workItem.setClosedTime(null);
        workItem.setStatus(DesignWorkItemStatus.IN_PROGRESS);
        this.updateOne(workItem, false);
    }

    /**
     * Preview all metadata changes accumulated under this WorkItem,
     * queried from ES by correlationId.
     */
    @Override
    public List<ModelChangesDTO> previewWorkItemChanges(Long id) {
        DesignWorkItem workItem = this.getById(id)
                .orElseThrow(() -> new IllegalArgumentException("WorkItem does not exist! {0}", id));
        Assert.notNull(workItem.getAppId(), "WorkItem {0} has no appId set!", id);

        List<Long> workItemIds = List.of(id);
        return versionControl.collectModelChanges(workItemIds);
    }

    /**
     * Preview the DDL SQL generated from the metadata changes of this WorkItem.
     */
    @Override
    public String previewWorkItemDDL(Long id) {
        DesignWorkItem workItem = this.getById(id)
                .orElseThrow(() -> new IllegalArgumentException("WorkItem does not exist! {0}", id));
        List<ModelChangesDTO> changes = previewWorkItemChanges(id);
        return versionDdl.generateDDL(appService.getFieldValue(workItem.getAppId(), DesignApp::getDatabaseType), changes);
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public void addToVersion(Long workItemId, Long versionId) {
        DesignAppVersion appVersion = appVersionService.getById(versionId)
                .orElseThrow(() -> new IllegalArgumentException("Version does not exist! {0}", versionId));
        Assert.isEqual(appVersion.getStatus(), DesignAppVersionStatus.DRAFT,
                "Only DRAFT versions can be modified! Current status: {0}", appVersion.getStatus());

        DesignWorkItem workItem = this.getById(workItemId)
                .orElseThrow(() -> new IllegalArgumentException("WorkItem does not exist! {0}", workItemId));
        if (workItem.getStatus() != DesignWorkItemStatus.DONE) {
            throw new BusinessException("Only DONE WorkItems can be added to a version! " +
                    "WorkItem {0} is {1}", workItemId, workItem.getStatus());
        }
        Assert.isEqual(workItem.getAppId(), appVersion.getAppId(),
                "WorkItem and Version must belong to the same App!");
        Assert.isTrue(workItem.getVersionId() == null,
                "WorkItem {0} already belongs to version {1}!", workItemId, workItem.getVersionId());

        workItem.setVersionId(versionId);
        this.updateOne(workItem);
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public void removeFromVersion(Long workItemId) {
        DesignWorkItem workItem = this.getById(workItemId)
                .orElseThrow(() -> new IllegalArgumentException("WorkItem does not exist! {0}", workItemId));
        Long versionId = workItem.getVersionId();
        Assert.notNull(versionId, "WorkItem {0} does not belong to any version!", workItemId);

        DesignAppVersion appVersion = appVersionService.getById(versionId)
                .orElseThrow(() -> new IllegalArgumentException("Version does not exist! {0}", versionId));
        Assert.isEqual(appVersion.getStatus(), DesignAppVersionStatus.DRAFT,
                "Only DRAFT versions can be modified! Current status: {0}", appVersion.getStatus());

        workItem.setVersionId(null);
        this.updateOne(workItem, false);
    }

}
