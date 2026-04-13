package io.softa.starter.studio.meta.controller;

import io.swagger.v3.oas.annotations.tags.Tag;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import io.softa.framework.web.controller.EntityController;
import io.softa.starter.studio.meta.entity.DesignView;
import io.softa.starter.studio.meta.service.DesignViewService;

/**
 * DesignView Model Controller
 */
@Tag(name = "DesignView")
@RestController
@RequestMapping("/DesignView")
public class DesignViewController extends EntityController<DesignViewService, DesignView, Long> {

}