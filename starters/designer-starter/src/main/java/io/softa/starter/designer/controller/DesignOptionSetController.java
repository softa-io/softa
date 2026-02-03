package io.softa.starter.designer.controller;

import io.swagger.v3.oas.annotations.tags.Tag;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import io.softa.framework.web.controller.EntityController;
import io.softa.starter.designer.entity.DesignOptionSet;
import io.softa.starter.designer.service.DesignOptionSetService;

/**
 * DesignOptionSet Model Controller
 */
@Tag(name = "DesignOptionSet")
@RestController
@RequestMapping("/DesignOptionSet")
public class DesignOptionSetController extends EntityController<DesignOptionSetService, DesignOptionSet, Long> {

}