package io.softa.starter.metadata.controller;

import io.swagger.v3.oas.annotations.tags.Tag;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import io.softa.framework.web.controller.EntityController;
import io.softa.starter.metadata.entity.SysFilter;
import io.softa.starter.metadata.service.SysFilterService;

/**
 * SysFilter Model Controller
 */
@Tag(name = "SysFilter")
@RestController
@RequestMapping("/SysFilter")
public class SysFilterController extends EntityController<SysFilterService, SysFilter, Long> {

}