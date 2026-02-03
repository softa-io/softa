package io.softa.starter.designer.upgrade;

import io.softa.framework.web.dto.MetadataUpgradePackage;
import io.softa.starter.designer.entity.DesignAppEnv;

import java.util.List;

public interface RemoteApiClient {

    /**
     * Remote call to upgrade API
     * @param appEnv        App environment
     * @param modelPackages List of runtime model data packages to be upgraded
     */
    void remoteUpgrade(DesignAppEnv appEnv, List<MetadataUpgradePackage> modelPackages);
}