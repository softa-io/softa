package io.softa.starter.designer.controller;

import io.swagger.v3.oas.annotations.tags.Tag;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import io.softa.framework.web.controller.EntityController;
import io.softa.starter.designer.entity.DesignModelValidation;
import io.softa.starter.designer.service.DesignModelValidationService;

/**
 * DesignModelValidation Model Controller
 */
@Tag(name = "DesignModelValidation")
@RestController
@RequestMapping("/DesignModelValidation")
public class DesignModelValidationController extends EntityController<DesignModelValidationService, DesignModelValidation, Long> {

}