package io.softa.starter.studio.release.service.impl;

import org.springframework.stereotype.Service;
import io.softa.framework.orm.service.impl.EntityServiceImpl;
import io.softa.starter.studio.release.entity.DesignDeploymentVersion;
import io.softa.starter.studio.release.service.DesignDeploymentVersionService;

/**
 * DesignDeploymentVersion Model Service Implementation
 */
@Service
public class DesignDeploymentVersionServiceImpl extends EntityServiceImpl<DesignDeploymentVersion, Long> implements DesignDeploymentVersionService {

}

