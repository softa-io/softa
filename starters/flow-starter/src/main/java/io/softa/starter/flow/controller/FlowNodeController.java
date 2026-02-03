package io.softa.starter.flow.controller;

import io.softa.framework.web.controller.EntityController;
import io.softa.starter.flow.entity.FlowNode;
import io.softa.starter.flow.service.FlowNodeService;
import io.swagger.v3.oas.annotations.tags.Tag;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * FlowNode Model Controller
 */
@Tag(name = "FlowNode")
@RestController
@RequestMapping("/FlowNode")
public class FlowNodeController extends EntityController<FlowNodeService, FlowNode, String> {

}