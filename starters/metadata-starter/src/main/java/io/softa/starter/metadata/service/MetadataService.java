package io.softa.starter.metadata.service;

import java.util.List;
import java.util.Map;

import io.softa.starter.metadata.controller.dto.MetaModelDTO;
import io.softa.starter.metadata.dto.MetadataUpgradePackage;

/**
 * Metadata Upgrade Service.
 */
public interface MetadataService {

    /**
     * Get the MetaModelDTO object by modelName
     *
     * @param modelName model name
     * @return metaModelDTO object
     */
    MetaModelDTO getMetaModelDTO(String modelName);

    /**
     * Upgrades the metadata of multiple models, all within a single transaction
     * to avoid refreshing the model pool repeatedly and missing dependency data.
     *
     * @param metadataPackages the metadata packages to Upgrade
     */
    void upgradeMetadata(List<MetadataUpgradePackage> metadataPackages);

    /**
     * Reload metadata.
     * The current replica will be unavailable if an exception occurs during the reload,
     * and the metadata needs to be fixed and reloaded.
     */
    void reloadMetadata();

    /**
     * Export runtime metadata rows for the given version-controlled model, scoped to an app.
     * <p>
     * Many runtimes host several apps side-by-side, so the studio must constrain the export
     * to the app it is synchronising: for models that carry an {@code appId} column the
     * filter is applied directly; for translation models (suffix {@code Trans}) the column
     * lives on the parent row, so the implementation resolves the parent app and matches
     * on {@code rowId}.
     *
     * @param modelName runtime model name
     * @param appId     app owning the rows; required
     * @return list of row data maps
     */
    List<Map<String, Object>> exportRuntimeMetadata(String modelName, Long appId);
}
