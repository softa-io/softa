package io.softa.starter.designer.controller;

import io.swagger.v3.oas.annotations.tags.Tag;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import io.softa.framework.web.controller.EntityController;
import io.softa.starter.designer.entity.DesignNavigation;
import io.softa.starter.designer.service.DesignNavigationService;

/**
 * DesignNavigation Model Controller
 */
@Tag(name = "DesignNavigation")
@RestController
@RequestMapping("/DesignNavigation")
public class DesignNavigationController extends EntityController<DesignNavigationService, DesignNavigation, Long> {

}