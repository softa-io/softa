package io.softa.starter.flow.service;

import java.util.List;
import java.util.Optional;

import io.softa.starter.flow.entity.FlowBundle;

/**
 * Service interface for persistent flow bundle CRUD and revision management.
 * <p>
 * All revision-management operations are keyed by {@code designId}
 * ({@link io.softa.starter.flow.entity.FlowDesign#getId()}), the stable, globally-unique
 * identity for a flow across revisions and tenants.
 * </p>
 */
public interface FlowBundleService {

    Optional<FlowBundle> findById(Long id);

    void saveBundle(FlowBundle bundle);

    Optional<FlowBundle> findActiveByDesignId(Long designId);

    Optional<FlowBundle> findByDesignIdAndRevision(Long designId, Integer revision);

    List<FlowBundle> listRevisionsByDesignId(Long designId);

    /** Return all currently active bundles (one per design); used for cache warm-up. */
    List<FlowBundle> getAllActiveFlow();

    int getNextRevision(Long designId);

    void markAsActive(Long designId, Integer revision);

    Optional<FlowBundle> activateBundle(Long bundleId);
}
