package io.softa.starter.studio.template.controller;

import io.swagger.v3.oas.annotations.tags.Tag;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import io.softa.framework.web.controller.EntityController;
import io.softa.starter.studio.template.entity.DesignFieldTypeDefault;
import io.softa.starter.studio.template.service.DesignFieldTypeDefaultService;

/**
 * DesignFieldTypeDefault Model Controller
 */
@Tag(name = "DesignFieldTypeDefault")
@RestController
@RequestMapping("/DesignFieldTypeDefault")
public class DesignFieldTypeDefaultController extends EntityController<DesignFieldTypeDefaultService, DesignFieldTypeDefault, Long> {

}
