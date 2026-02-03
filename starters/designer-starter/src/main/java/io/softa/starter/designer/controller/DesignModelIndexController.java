package io.softa.starter.designer.controller;

import io.swagger.v3.oas.annotations.tags.Tag;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import io.softa.framework.web.controller.EntityController;
import io.softa.starter.designer.entity.DesignModelIndex;
import io.softa.starter.designer.service.DesignModelIndexService;

/**
 * DesignModelIndex Model Controller
 */
@Tag(name = "DesignModelIndex")
@RestController
@RequestMapping("/DesignModelIndex")
public class DesignModelIndexController extends EntityController<DesignModelIndexService, DesignModelIndex, Long> {

}