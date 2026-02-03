package io.softa.starter.metadata.service;

import io.softa.starter.metadata.entity.SysConfig;
import io.softa.framework.orm.service.EntityService;

import java.util.Optional;

/**
 * SysConfig Model Service Interface
 */
public interface SysConfigService extends EntityService<SysConfig, Long> {

    /**
     * Query system config by code
     *
     * @param code config code
     * @return optional of SysConfig
     */
    Optional<SysConfig> getConfigByCode(String code);

}