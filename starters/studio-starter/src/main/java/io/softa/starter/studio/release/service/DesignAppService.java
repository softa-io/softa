package io.softa.starter.studio.release.service;

import io.softa.framework.orm.service.EntityService;
import io.softa.starter.studio.release.entity.DesignApp;
import io.softa.starter.studio.release.enums.DesignAppStatus;

/**
 * DesignApp Model Service Interface
 */
public interface DesignAppService extends EntityService<DesignApp, Long> {

    /**
     * Transition the app status with business validation.
     *
     * @param id app ID
     * @param targetStatus target status
     */
    void transitionStatus(Long id, DesignAppStatus targetStatus);

}
