package io.softa.starter.designer.version;

import io.softa.starter.designer.dto.ModelChangesDTO;
import io.softa.starter.designer.entity.DesignAppEnv;

import java.time.LocalDateTime;

/**
 * Version control for change data
 */
public interface VersionControl {

    /**
     * Get the change data of the model, including created, updated, and deleted data.
     *
     * @param appEnv app environment
     * @param versionedModel Version controlled design model name
     * @param startTime Start time of the change
     * @return ModelChangesDTO
     */
    ModelChangesDTO getModelChanges(DesignAppEnv appEnv, String versionedModel, LocalDateTime startTime);
}