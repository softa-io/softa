package io.softa.starter.flow.service;

import io.softa.framework.orm.service.EntityService;
import io.softa.starter.flow.dto.FlowDesignCreateRequest;
import io.softa.starter.flow.dto.FlowDesignDuplicateRequest;
import io.softa.starter.flow.dto.FlowDesignSaveRequest;
import io.softa.starter.flow.dto.FlowDesignStatusView;
import io.softa.starter.flow.entity.FlowDesign;

/**
 * Manages the single working-copy (draft) of each flow design.
 *
 * <p>The flow editor talks to the dedicated {@code /flow/designs} REST surface,
 * which delegates here. The generic {@code /FlowDesign} model API remains available
 * as the platform data plane and is intentionally not intercepted.</p>
 */
public interface FlowDesignService extends EntityService<FlowDesign, Long> {

    /** Create a new draft; the flow code must be unique within the tenant. */
    FlowDesign createDesign(FlowDesignCreateRequest request);

    /**
     * Persist the editor's canvas (auto-save). Applies the optimistic-lock version
     * from the request; a stale version is rejected with a version conflict.
     * Never runs semantic validation — drafts are allowed to be broken.
     *
     * @return the updated draft (with the incremented version)
     */
    FlowDesign saveDraft(Long id, FlowDesignSaveRequest request);

    /** Duplicate a draft under a fresh flow code (publish state is not copied). */
    FlowDesign duplicateDesign(Long id, FlowDesignDuplicateRequest request);

    /** Draft-vs-published status for the editor header (revision badge + dirty flag). */
    FlowDesignStatusView getStatus(Long id);

    /**
     * Update the draft's publish markers ({@link FlowDesign#getPublishedRevision()} and
     * {@link FlowDesign#getPublishedChecksum()}) after a successful publish.
     * Called by {@link FlowPublishService} once the bundle has been persisted.
     */
    void upsertFromPublish(Long designId, Integer publishedRevision);

    /**
     * Overwrite the draft's {@code designJson} with the snapshot stored in the specified bundle.
     * Effectively rolls the canvas back to the state at that publish point.
     *
     * @param designId the target FlowDesign id
     * @param bundleId the source FlowBundle id whose designJson to restore
     * @return the updated draft
     */
    FlowDesign restoreFromBundle(Long designId, Long bundleId);
}
