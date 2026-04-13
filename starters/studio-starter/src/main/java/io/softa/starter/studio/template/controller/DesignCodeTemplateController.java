package io.softa.starter.studio.template.controller;

import io.swagger.v3.oas.annotations.tags.Tag;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import io.softa.framework.web.controller.EntityController;
import io.softa.starter.studio.template.entity.DesignCodeTemplate;
import io.softa.starter.studio.template.service.DesignCodeTemplateService;

/**
 * DesignCodeTemplate Model Controller
 */
@Tag(name = "DesignCodeTemplate")
@RestController
@RequestMapping("/DesignCodeTemplate")
public class DesignCodeTemplateController extends EntityController<DesignCodeTemplateService, DesignCodeTemplate, Long> {

}
