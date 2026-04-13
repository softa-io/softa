package io.softa.starter.studio.meta.controller;

import io.swagger.v3.oas.annotations.tags.Tag;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import io.softa.framework.web.controller.EntityController;
import io.softa.starter.studio.meta.entity.DesignField;
import io.softa.starter.studio.meta.service.DesignFieldService;

/**
 * DesignField Model Controller
 */
@Tag(name = "DesignField")
@RestController
@RequestMapping("/DesignField")
public class DesignFieldController extends EntityController<DesignFieldService, DesignField, Long> {

}