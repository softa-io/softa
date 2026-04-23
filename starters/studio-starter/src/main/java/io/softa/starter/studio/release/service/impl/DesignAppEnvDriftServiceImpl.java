package io.softa.starter.studio.release.service.impl;

import org.springframework.stereotype.Service;

import io.softa.framework.orm.service.impl.EntityServiceImpl;
import io.softa.starter.studio.release.entity.DesignAppEnvDrift;
import io.softa.starter.studio.release.service.DesignAppEnvDriftService;

/**
 * DesignAppEnvDrift Model Service Implementation
 */
@Service
public class DesignAppEnvDriftServiceImpl extends EntityServiceImpl<DesignAppEnvDrift, Long>
        implements DesignAppEnvDriftService {
}
