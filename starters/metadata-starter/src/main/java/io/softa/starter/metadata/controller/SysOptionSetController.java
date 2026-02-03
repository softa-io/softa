package io.softa.starter.metadata.controller;

import io.softa.framework.web.controller.EntityController;
import io.softa.starter.metadata.entity.SysOptionSet;
import io.softa.starter.metadata.service.SysOptionSetService;
import io.swagger.v3.oas.annotations.tags.Tag;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * SysOptionSet Model Controller
 */
@Tag(name = "SysOptionSet")
@RestController
@RequestMapping("/SysOptionSet")
public class SysOptionSetController extends EntityController<SysOptionSetService, SysOptionSet, Long> {

}