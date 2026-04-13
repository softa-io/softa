package io.softa.starter.studio.release.service;

import java.util.List;

import io.softa.framework.orm.service.EntityService;
import io.softa.starter.studio.release.dto.ModelChangesDTO;
import io.softa.starter.studio.release.entity.DesignAppEnv;

/**
 * DesignAppEnv Model Service Interface
 */
public interface DesignAppEnvService extends EntityService<DesignAppEnv, Long> {

    /**
     * Take a snapshot of the expected runtime metadata state for the given environment.
     * <p>
     * Computes the full expected state by applying {@code mergedChanges} on top of the
     * previous snapshot (or empty baseline for the first deployment).
     * The result is stored as a JSON snapshot on {@code DesignAppEnvSnapshot} (OneToOne with Env).
     *
     * @param envId         Environment ID
     * @param deploymentId  Deployment ID that produced this snapshot
     * @param mergedChanges the merged version changes that were deployed
     */
    void takeSnapshot(Long envId, Long deploymentId, List<ModelChangesDTO> mergedChanges);

    /**
     * Compare the design-time snapshot with the actual runtime metadata for the given environment.
     * Detects drift caused by direct SQL changes, unsynced runtime modifications, etc.
     * <p>
     * Uses the synchronized primary id to match rows between the snapshot and runtime data.
     *
     * @param envId Environment ID
     * @return List of model changes representing the drift between snapshot and runtime
     */
    List<ModelChangesDTO> compareDesignWithRuntime(Long envId);

}
