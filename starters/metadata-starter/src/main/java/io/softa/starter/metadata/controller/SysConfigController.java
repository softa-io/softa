package io.softa.starter.metadata.controller;

import io.swagger.v3.oas.annotations.tags.Tag;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import io.softa.framework.web.controller.EntityController;
import io.softa.starter.metadata.entity.SysConfig;
import io.softa.starter.metadata.service.SysConfigService;

/**
 * SysConfig Model Controller
 */
@Tag(name = "SysConfig")
@RestController
@RequestMapping("/SysConfig")
public class SysConfigController extends EntityController<SysConfigService, SysConfig, Long> {

}