package io.softa.starter.studio.template.controller;

import io.swagger.v3.oas.annotations.tags.Tag;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import io.softa.framework.web.controller.EntityController;
import io.softa.starter.studio.template.entity.DesignSqlTemplate;
import io.softa.starter.studio.template.service.DesignSqlTemplateService;

/**
 * DesignSqlTemplate Model Controller
 */
@Tag(name = "DesignSqlTemplate")
@RestController
@RequestMapping("/DesignSqlTemplate")
public class DesignSqlTemplateController extends EntityController<DesignSqlTemplateService, DesignSqlTemplate, Long> {

}
