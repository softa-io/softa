package io.softa.starter.flow.controller;

import io.swagger.v3.oas.annotations.tags.Tag;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import io.softa.framework.web.controller.EntityController;
import io.softa.starter.flow.entity.FlowInstance;
import io.softa.starter.flow.service.FlowInstanceService;

/**
 * FlowInstance Model Controller
 */
@Tag(name = "FlowInstance")
@RestController
@RequestMapping("/FlowInstance")
public class FlowInstanceController extends EntityController<FlowInstanceService, FlowInstance, Long> {

}