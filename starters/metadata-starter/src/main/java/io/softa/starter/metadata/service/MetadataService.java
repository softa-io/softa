package io.softa.starter.metadata.service;

import java.util.List;
import java.util.Map;

import io.softa.framework.web.dto.MetadataUpgradePackage;
import io.softa.starter.metadata.controller.dto.MetaModelDTO;

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
     * Export all runtime metadata rows for a given version-controlled model.
     * Returns all scalar fields as row maps for cross-environment comparison.
     *
     * @param modelName runtime model name
     * @return list of row data maps
     */
    List<Map<String, Object>> exportRuntimeMetadata(String modelName);
}
