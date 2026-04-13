package io.softa.starter.studio.template.controller;

import io.swagger.v3.oas.annotations.tags.Tag;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import io.softa.framework.web.controller.EntityController;
import io.softa.starter.studio.template.entity.DesignFieldCodeMapping;
import io.softa.starter.studio.template.service.DesignFieldCodeMappingService;

/**
 * DesignFieldCodeMapping Model Controller
 */
@Tag(name = "DesignFieldCodeMapping")
@RestController
@RequestMapping("/DesignFieldCodeMapping")
public class DesignFieldCodeMappingController extends EntityController<DesignFieldCodeMappingService, DesignFieldCodeMapping, Long> {

}
