package io.softa.starter.designer.controller;

import io.softa.framework.web.controller.EntityController;
import io.softa.starter.designer.entity.DesignApp;
import io.softa.starter.designer.service.DesignAppService;
import io.swagger.v3.oas.annotations.tags.Tag;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * DesignApp Model Controller
 */
@Tag(name = "DesignApp")
@RestController
@RequestMapping("/DesignApp")
public class DesignAppController extends EntityController<DesignAppService, DesignApp, Long> {

}