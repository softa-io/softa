package io.softa.starter.metadata.service.impl;

import io.softa.framework.orm.domain.Filters;
import io.softa.framework.orm.domain.FlexQuery;
import io.softa.framework.orm.service.impl.EntityServiceImpl;
import io.softa.starter.metadata.entity.SysConfig;
import io.softa.starter.metadata.service.SysConfigService;
import org.springframework.stereotype.Service;

import java.util.Optional;

/**
 * SysConfig Model Service Implementation
 */
@Service
public class SysConfigServiceImpl extends EntityServiceImpl<SysConfig, Long> implements SysConfigService {

    /**
     * Query system config by code
     *
     * @param code config code
     * @return Optional of SysConfig
     */
    @Override
    public Optional<SysConfig> getConfigByCode(String code) {
        return this.searchOne(new FlexQuery(new Filters().eq(SysConfig::getCode, code)));
    }

}