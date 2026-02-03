package io.softa.starter.designer.controller;

import io.swagger.v3.oas.annotations.tags.Tag;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import io.softa.framework.web.controller.EntityController;
import io.softa.starter.designer.entity.DesignOptionItem;
import io.softa.starter.designer.service.DesignOptionItemService;

/**
 * DesignOptionItem Model Controller
 */
@Tag(name = "DesignOptionItem")
@RestController
@RequestMapping("/DesignOptionItem")
public class DesignOptionItemController extends EntityController<DesignOptionItemService, DesignOptionItem, Long> {

}