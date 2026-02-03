package io.softa.starter.flow.controller;

import io.swagger.v3.oas.annotations.tags.Tag;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import io.softa.framework.web.controller.EntityController;
import io.softa.starter.flow.entity.FlowStage;
import io.softa.starter.flow.service.FlowStageService;

/**
 * FlowStage Model Controller
 */
@Tag(name = "FlowStage")
@RestController
@RequestMapping("/FlowStage")
public class FlowStageController extends EntityController<FlowStageService, FlowStage, String> {

}