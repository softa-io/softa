package io.softa.starter.studio.release.controller;

import io.swagger.v3.oas.annotations.tags.Tag;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import io.softa.framework.web.controller.EntityController;
import io.softa.starter.studio.release.entity.DesignDeploymentVersion;
import io.softa.starter.studio.release.service.DesignDeploymentVersionService;

/**
 * DesignDeploymentVersion Model Controller — read-only access to deployment-version audit records.
 * DesignDeploymentVersion records are auto-created when a Deployment is generated during deployment.
 */
@Tag(name = "DesignDeploymentVersion")
@RestController
@RequestMapping("/DesignDeploymentVersion")
public class DesignDeploymentVersionController extends EntityController<DesignDeploymentVersionService, DesignDeploymentVersion, Long> {

}

