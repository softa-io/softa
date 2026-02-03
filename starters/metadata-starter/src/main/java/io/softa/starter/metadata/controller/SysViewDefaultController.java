package io.softa.starter.metadata.controller;

import io.swagger.v3.oas.annotations.tags.Tag;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import io.softa.framework.web.controller.EntityController;
import io.softa.starter.metadata.entity.SysViewDefault;
import io.softa.starter.metadata.service.SysViewDefaultService;

/**
 * SysViewDefault Model Controller
 */
@Tag(name = "SysViewDefault")
@RestController
@RequestMapping("/SysViewDefault")
public class SysViewDefaultController extends EntityController<SysViewDefaultService, SysViewDefault, Long> {

}