package io.softa.starter.studio.release.upgrade;

import java.util.List;
import java.util.Map;

import io.softa.framework.web.dto.MetadataUpgradePackage;
import io.softa.starter.studio.release.entity.DesignAppEnv;

public interface RemoteApiClient {

    /**
     * Remote call to upgrade API
     * @param appEnv        App environment
     * @param modelPackages List of runtime model data packages to be upgraded
     */
    void remoteUpgrade(DesignAppEnv appEnv, List<MetadataUpgradePackage> modelPackages);

    /**
     * Fetch runtime metadata for a specific model from the remote environment.
     *
     * @param appEnv           target environment
     * @param runtimeModelName runtime model name (e.g. "SysModel")
     * @return list of runtime row data
     */
    List<Map<String, Object>> fetchRuntimeMetadata(DesignAppEnv appEnv, String runtimeModelName);
}