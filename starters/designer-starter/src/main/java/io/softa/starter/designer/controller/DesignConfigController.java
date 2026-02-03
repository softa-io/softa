package io.softa.starter.designer.controller;

import io.swagger.v3.oas.annotations.tags.Tag;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import io.softa.framework.web.controller.EntityController;
import io.softa.starter.designer.entity.DesignConfig;
import io.softa.starter.designer.service.DesignConfigService;

/**
 * DesignConfig Model Controller
 */
@Tag(name = "DesignConfig")
@RestController
@RequestMapping("/DesignConfig")
public class DesignConfigController extends EntityController<DesignConfigService, DesignConfig, Long> {

}