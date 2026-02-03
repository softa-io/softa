package io.softa.starter.designer.service.impl;

import org.springframework.stereotype.Service;
import io.softa.framework.orm.service.impl.EntityServiceImpl;
import io.softa.starter.designer.entity.DesignConfig;
import io.softa.starter.designer.service.DesignConfigService;

/**
 * DesignConfig Model Service Implementation
 */
@Service
public class DesignConfigServiceImpl extends EntityServiceImpl<DesignConfig, Long> implements DesignConfigService {

}