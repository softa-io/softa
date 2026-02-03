package io.softa.starter.metadata.controller;

import io.swagger.v3.oas.annotations.tags.Tag;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import io.softa.framework.web.controller.EntityController;
import io.softa.starter.metadata.entity.SysOptionItem;
import io.softa.starter.metadata.service.SysOptionItemService;

/**
 * SysOptionItem Model Controller
 */
@Tag(name = "SysOptionItem")
@RestController
@RequestMapping("/SysOptionItem")
public class SysOptionItemController extends EntityController<SysOptionItemService, SysOptionItem, Long> {

}