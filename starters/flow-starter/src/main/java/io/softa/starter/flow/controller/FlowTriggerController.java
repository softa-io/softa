package io.softa.starter.flow.controller;

import io.swagger.v3.oas.annotations.tags.Tag;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import io.softa.framework.web.controller.EntityController;
import io.softa.starter.flow.entity.FlowTrigger;
import io.softa.starter.flow.service.FlowTriggerService;

/**
 * FlowTrigger Model Controller
 */
@Tag(name = "FlowTrigger")
@RestController
@RequestMapping("/FlowTrigger")
public class FlowTriggerController extends EntityController<FlowTriggerService, FlowTrigger, Long> {

}