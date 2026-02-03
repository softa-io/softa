package io.softa.starter.metadata.service;

import io.softa.framework.web.dto.MetadataUpgradePackage;

import java.util.List;

/**
 * Metadata Upgrade Service.
 */
public interface MetadataService {

    /**
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
}
