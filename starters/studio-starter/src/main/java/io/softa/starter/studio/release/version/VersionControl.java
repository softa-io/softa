package io.softa.starter.studio.release.version;

import java.util.List;

import io.softa.starter.studio.release.dto.ModelChangesDTO;

/**
 * Version control for change data
 */
public interface VersionControl {

    /**
     * Collect model-level changes for all version-controlled models from the specified WorkItems.
     *
     * @param workItemIds list of WorkItem IDs whose changes to aggregate
     * @return list of model-level change summaries, excluding empty models
     */
    List<ModelChangesDTO> collectModelChanges(List<Long> workItemIds);

}
