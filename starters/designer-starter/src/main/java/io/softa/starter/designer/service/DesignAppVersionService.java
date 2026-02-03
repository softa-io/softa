package io.softa.starter.designer.service;

import io.softa.framework.orm.service.EntityService;
import io.softa.starter.designer.entity.DesignAppVersion;

/**
 * DesignAppVersion Model Service Interface
 */
public interface DesignAppVersionService extends EntityService<DesignAppVersion, Long> {


    /**
     * Create a new App version.
     *
     * @param appVersion App version object
     * @return id
     */
    Long createOne(DesignAppVersion appVersion);

    /**
     * Reload App env changes to current version.
     *
     * @param id Version ID
     */
    boolean reloadAppVersion(Long id);

    /**
     * Publish the version to the target environment.
     *
     * @param id    Version ID
     */
    void publish(Long id);
}