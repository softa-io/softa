package io.softa.starter.metadata.controller;

import io.swagger.v3.oas.annotations.tags.Tag;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import io.softa.framework.web.controller.EntityController;
import io.softa.starter.metadata.entity.SysNavigation;
import io.softa.starter.metadata.service.SysNavigationService;

/**
 * SysNavigation Model Controller
 */
@Tag(name = "SysNavigation")
@RestController
@RequestMapping("/SysNavigation")
public class SysNavigationController extends EntityController<SysNavigationService, SysNavigation, Long> {

}