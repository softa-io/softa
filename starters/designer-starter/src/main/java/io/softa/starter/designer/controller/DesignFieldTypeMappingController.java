package io.softa.starter.designer.controller;

import io.swagger.v3.oas.annotations.tags.Tag;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import io.softa.framework.web.controller.EntityController;
import io.softa.starter.designer.entity.DesignFieldTypeMapping;
import io.softa.starter.designer.service.DesignFieldTypeMappingService;

/**
 * DesignFieldTypeMapping Model Controller
 */
@Tag(name = "DesignFieldTypeMapping")
@RestController
@RequestMapping("/DesignFieldTypeMapping")
public class DesignFieldTypeMappingController extends EntityController<DesignFieldTypeMappingService, DesignFieldTypeMapping, Long> {

}