package io.softa.starter.flow.service;

import java.util.List;
import java.util.Optional;

import io.softa.starter.flow.design.DesignFlowDefinition;
import io.softa.starter.flow.dto.FlowBundleSummaryView;
import io.softa.starter.flow.runtime.bundle.CompiledFlowDefinition;

/**
 * Publishes compiled flow bundles into the runtime registry, with revision management.
 */
public interface FlowPublishService {

    /**
     * Editor publish: read the saved draft for {@code designId} (single source of truth,
     * no canvas payload accepted), compile it, publish a new revision, stamp the change
     * description on the bundle row, and update the draft's publish markers.
     *
     * @return a lightweight summary of the new revision
     */
    FlowBundleSummaryView publishDraft(Long designId, String changeDescription);

    /**
     * Compile the supplied {@code definition} and publish it.
     * {@code designId} is optional — pass null for headless / test publishing.
     */
    CompiledFlowDefinition publish(Long designId, DesignFlowDefinition definition);

    /**
     * Get the current (latest) compiled definition for a design id.
     */
    Optional<CompiledFlowDefinition> getLatest(Long designId);

    /**
     * List all active revisions for a design id, ordered by revision descending.
     */
    List<CompiledFlowDefinition> getRevisions(Long designId);

    /**
     * Activate the specified published bundle as its design's effective revision
     * (rollback and roll-forward are the same operation). The target bundle's
     * {@code designId} is read from the bundle itself.
     */
    Optional<CompiledFlowDefinition> activateBundle(Long bundleId);

}
