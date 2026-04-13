package io.softa.starter.studio.template.controller;

import io.swagger.v3.oas.annotations.tags.Tag;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import io.softa.framework.web.controller.EntityController;
import io.softa.starter.studio.template.entity.DesignFieldDbMapping;
import io.softa.starter.studio.template.service.DesignFieldDbMappingService;

/**
 * DesignFieldDbMapping Model Controller
 */
@Tag(name = "DesignFieldDbMapping")
@RestController
@RequestMapping("/DesignFieldDbMapping")
public class DesignFieldDbMappingController extends EntityController<DesignFieldDbMappingService, DesignFieldDbMapping, Long> {

}
