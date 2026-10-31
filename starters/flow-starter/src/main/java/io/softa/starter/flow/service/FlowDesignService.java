package io.softa.starter.flow.service;

import java.util.List;

import io.softa.framework.orm.service.EntityService;
import io.softa.starter.flow.dto.FlowDesignCreateRequest;
import io.softa.starter.flow.dto.FlowDesignDuplicateRequest;
import io.softa.starter.flow.dto.FlowDesignSaveRequest;
import io.softa.starter.flow.dto.ApprovalModelView;
import io.softa.starter.flow.dto.FlowDesignStatusView;
import io.softa.starter.flow.entity.FlowDesign;

/**
 * Manages the single working-copy (draft) of each flow design.
 *
 * <p>The flow editor talks to the dedicated {@code /flow/designs} REST surface,
 * which delegates here. The generic {@code /FlowDesign} model API remains available
 * as the platform data plane and is intentionally not intercepted.</p>
 */
public interface FlowDesignService extends EntityService<FlowDesign, String> {

    /** Create a new draft; the flow name must be unique within the tenant. */
    FlowDesign createDesign(FlowDesignCreateRequest request);

    /**
     * Persist the editor's canvas (auto-save). Applies the optimistic-lock version
     * from the request; a stale version is rejected with a version conflict.
     * Never runs semantic validation — drafts are allowed to be broken.
     *
     * @return the updated draft (with the incremented version)
     */
    FlowDesign saveDraft(String id, FlowDesignSaveRequest request);

    /** Duplicate a draft under a fresh name (publish state is not copied). */
    FlowDesign duplicateDesign(String id, FlowDesignDuplicateRequest request);

    /** Draft-vs-published status for the editor header (revision badge, dirty flag, enabled). */
    FlowDesignStatusView getStatus(String id);

    /**
     * Switch the flow off: it starts nothing more — no submission, change, schedule, API call or parent flow
     * reaches it — while the instances already running carry on. The effective revision stays as it is, so
     * {@link #enable} brings back the same one. Refused while an enabled flow calls it as a subflow.
     *
     * @return the flow's status
     */
    FlowDesignStatusView disable(String id);

    /**
     * Switch the flow back on, on the revision that was effective; a no-op when it is on. Refused for an approval
     * flow whose document has another approval flow enabled.
     */
    FlowDesignStatusView enable(String id);

    /** The documents the application can approve, each with its approval flow, if it has one; by label. */
    List<ApprovalModelView> listApprovalModels();

    /**
     * Update the draft's publish markers ({@code FlowDesign#getPublishedRevision()} and
     * {@code FlowDesign#getPublishedChecksum()}) after a successful publish.
     * Called by {@link FlowPublishService} once the bundle has been persisted.
     */
    void upsertFromPublish(String flowId, Integer publishedRevision);

    /**
     * Overwrite the draft's {@code designJson} with the snapshot stored in the specified bundle.
     * Effectively rolls the canvas back to the state at that publish point.
     *
     * @param flowId the target FlowDesign id
     * @param bundleId the source FlowBundle id whose designJson to restore
     * @return the updated draft
     */
    FlowDesign restoreFromBundle(String flowId, Long bundleId);
}
