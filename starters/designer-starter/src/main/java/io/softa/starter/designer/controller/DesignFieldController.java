package io.softa.starter.designer.controller;

import io.swagger.v3.oas.annotations.tags.Tag;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import io.softa.framework.web.controller.EntityController;
import io.softa.starter.designer.entity.DesignField;
import io.softa.starter.designer.service.DesignFieldService;

/**
 * DesignField Model Controller
 */
@Tag(name = "DesignField")
@RestController
@RequestMapping("/DesignField")
public class DesignFieldController extends EntityController<DesignFieldService, DesignField, Long> {

}