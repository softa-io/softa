package io.softa.starter.studio.release.event;

import java.util.List;

import io.softa.starter.studio.release.dto.ModelChangesDTO;

/**
 * Published after a deployment succeeds so snapshot rebuilding can run after commit.
 */
public record DesignDeploymentSnapshotEvent(
        Long envId,
        Long deploymentId,
        List<ModelChangesDTO> mergedChanges
) {}
