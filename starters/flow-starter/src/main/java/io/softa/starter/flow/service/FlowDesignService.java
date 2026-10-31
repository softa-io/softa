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

    /**
     * Duplicate a flow under a fresh name as an unpublished draft (publish state is not copied) — an approval flow too,
     * for another country or company: its copy approves the same document with the same submit condition, which it
     * must change before it is published beside the source.
     */
    FlowDesign duplicateDesign(String id, FlowDesignDuplicateRequest request);

    /** Draft-vs-published status for the editor header (revision badge, dirty flag, enabled). */
    FlowDesignStatusView getStatus(String id);

    /**
     * Switch the flow off: it starts nothing more — no submission, change, schedule, API call or parent flow
     * reaches it. The effective revision stays as it is, so {@link #enable} brings back the same one. Refused while
     * any of its instances has not finished — a record still in approval — and while a flow that may still run it
     * as a subflow calls it: an enabled flow's effective revision, or the revision an unfinished instance runs on.
     *
     * @return the flow's status
     */
    FlowDesignStatusView disable(String id);

    /**
     * Switch the flow back on, on the revision that was effective; a no-op when it is on. Refused for an approval
     * flow when another enabled approval flow of its document has the same submit condition, and for a Compute flow
     * declaring an output another enabled Compute flow of its model declares.
     */
    FlowDesignStatusView enable(String id);

    /** The documents the application can approve, each with the names of its approval flows; by label. */
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
