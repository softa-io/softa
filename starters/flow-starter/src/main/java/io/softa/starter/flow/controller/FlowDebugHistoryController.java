package io.softa.starter.flow.controller;

import io.swagger.v3.oas.annotations.tags.Tag;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import io.softa.framework.web.controller.EntityController;
import io.softa.starter.flow.entity.FlowDebugHistory;
import io.softa.starter.flow.service.FlowDebugHistoryService;

/**
 * FlowDebugHistory Model Controller
 */
@Tag(name = "FlowDebugHistory")
@RestController
@RequestMapping("/FlowDebugHistory")
public class FlowDebugHistoryController extends EntityController<FlowDebugHistoryService, FlowDebugHistory, String> {

}