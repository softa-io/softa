package io.softa.starter.studio.release.service;

import java.util.List;

import io.softa.framework.orm.service.EntityService;
import io.softa.starter.studio.release.dto.ModelChangesDTO;
import io.softa.starter.studio.release.entity.DesignAppVersion;

/**
 * DesignAppVersion Model Service Interface
 */
public interface DesignAppVersionService extends EntityService<DesignAppVersion, Long> {

    /**
     * Deploy a Version to an Environment.
     *
     * @param versionId Version ID
     * @param envId Environment ID
     * @return deployment ID
     */
    Long deployToEnv(Long versionId, Long envId);

    /**
     * Seal the version: aggregate changes from selected WorkItems, generate DDL,
     * compute diffHash, and transition status to SEALED (immutable).
     *
     * @param id Version ID
     */
    void sealVersion(Long id);

    /**
     * Unseal a SEALED version back to DRAFT, clearing its versionedContent and diffHash.
     * <p>
     * This is only allowed when:
     * <ul>
     *   <li>The version is in SEALED status (FROZEN versions cannot be unsealed)</li>
     *   <li>No Deployment references this version (it has not been deployed)</li>
     * </ul>
     *
     * @param id Version ID
     */
    void unsealVersion(Long id);

    /**
     * Freeze the version, marking that it has been deployed
     * and can no longer be changed.
     *
     * @param id Version ID
     */
    void freezeVersion(Long id);

    /**
     * Preview the merged content of the version without modifying its status.
     *
     * @param id Version ID
     * @return list of model-level change summaries
     */
    List<ModelChangesDTO> previewVersion(Long id);

    /**
     * Preview the DDL SQL generated from the version's change data.
     * Version does not store DDL — DDL is always generated on the fly from change data.
     *
     * @param id Version ID
     * @return DDL SQL string (CREATE TABLE, ALTER TABLE, DROP TABLE, indexes)
     */
    String previewVersionDDL(Long id);

    /**
     * Return the released versions that should be merged when deploying from
     * {@code fromVersionId} (exclusive) to {@code toVersionId} (inclusive).
     * <p>
     * The merge order is the app's release stream ordered by {@code sealedTime ASC}.
     * Only SEALED/FROZEN versions in the interval {@code (fromVersion, toVersion]} are returned.
     * If {@code fromVersionId} is null, the interval starts from the beginning of the release stream.
     *
     * @param fromVersionId starting version (exclusive), or null for root
     * @param toVersionId   ending version (inclusive)
     * @return ordered list from oldest released version in range to target version
     */
    List<DesignAppVersion> getVersionsForMerge(Long fromVersionId, Long toVersionId);

}
