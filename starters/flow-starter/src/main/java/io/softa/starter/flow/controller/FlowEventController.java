package io.softa.starter.flow.controller;

import io.swagger.v3.oas.annotations.tags.Tag;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import io.softa.framework.web.controller.EntityController;
import io.softa.starter.flow.entity.FlowEvent;
import io.softa.starter.flow.service.FlowEventService;

/**
 * FlowEvent Model Controller
 */
@Tag(name = "FlowEvent")
@RestController
@RequestMapping("/FlowEvent")
public class FlowEventController extends EntityController<FlowEventService, FlowEvent, Long> {

}