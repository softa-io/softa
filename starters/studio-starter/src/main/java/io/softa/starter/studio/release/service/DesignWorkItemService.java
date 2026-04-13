package io.softa.starter.studio.release.service;

import java.util.List;

import io.softa.framework.orm.service.EntityService;
import io.softa.starter.studio.release.dto.ModelChangesDTO;
import io.softa.starter.studio.release.entity.DesignWorkItem;

/**
 * DesignWorkItem Model Service Interface
 */
public interface DesignWorkItemService extends EntityService<DesignWorkItem, Long> {

    /**
     * Complete the WorkItem and transition status to DONE.
     * The WorkItem must be in IN_PROGRESS status.
     *
     * @param id WorkItem ID
     */
    void doneWorkItem(Long id);

    /**
     * Preview all metadata changes accumulated under this WorkItem,
     * queried from ES by correlationId.
     *
     * @param id WorkItem ID
     * @return list of model-level change summaries
     */
    List<ModelChangesDTO> previewWorkItemChanges(Long id);

    /**
     * Preview the DDL SQL generated from the metadata changes of this WorkItem.
     *
     * @param id WorkItem ID
     * @return DDL SQL string (CREATE TABLE, ALTER TABLE, DROP TABLE, indexes)
     */
    String previewWorkItemDDL(Long id);

    /**
     * Add a DONE WorkItem to a DRAFT Version.
     *
     * @param workItemId WorkItem ID
     * @param versionId Version ID
     */
    void addToVersion(Long workItemId, Long versionId);

    /**
     * Remove a WorkItem from a Version.
     *
     * @param workItemId WorkItem ID
     */
    void removeFromVersion(Long workItemId);

    /**
     * Cancel the WorkItem. Only allowed for IN_PROGRESS, DONE, or DEFERRED.
     *
     * @param id WorkItem ID
     */
    void cancelWorkItem(Long id);

    /**
     * Defer the WorkItem. Only allowed for IN_PROGRESS.
     *
     * @param id WorkItem ID
     */
    void deferWorkItem(Long id);

    /**
     * Reopen a DONE, CANCELLED, or DEFERRED WorkItem back to IN_PROGRESS.
     *
     * @param id WorkItem ID
     */
    void reopenWorkItem(Long id);

}
