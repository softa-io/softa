package io.softa.starter.flow.controller;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import io.softa.framework.orm.domain.Filters;
import io.softa.framework.orm.domain.FlexQuery;
import io.softa.framework.orm.domain.Orders;
import io.softa.framework.orm.domain.Page;
import io.softa.framework.web.response.ApiResponse;
import io.softa.starter.flow.dto.FlowEventView;
import io.softa.starter.flow.entity.FlowEvent;
import io.softa.starter.flow.service.FlowEventService;

/**
 * REST endpoint for querying the trigger event log.
 */
@Tag(name = "Flow Events")
@RestController
@RequestMapping("/flow/events")
public class FlowEventController {

    private final FlowEventService eventService;

    public FlowEventController(FlowEventService eventService) {
        this.eventService = eventService;
    }

    @GetMapping
    @Operation(summary = "Search trigger events",
            description = "Paged trigger event log, newest first. All filters are optional and combine "
                    + "with AND. The trigger-parameters payload is excluded from list rows.")
    public ApiResponse<Page<FlowEventView>> search(@RequestParam(required = false) String flowCode,
                                                   @RequestParam(required = false) String sourceModel,
                                                   @RequestParam(required = false) String sourceRowId,
                                                   @RequestParam(required = false) String instanceId,
                                                   @RequestParam(required = false) Boolean success,
                                                   @RequestParam(defaultValue = "1") Integer pageNumber,
                                                   @RequestParam(defaultValue = "50") Integer pageSize) {
        Filters filters = new Filters();
        if (flowCode != null) {
            filters.eq(FlowEvent::getFlowCode, flowCode);
        }
        if (sourceModel != null) {
            filters.eq(FlowEvent::getSourceModel, sourceModel);
        }
        if (sourceRowId != null) {
            filters.eq(FlowEvent::getSourceRowId, sourceRowId);
        }
        if (instanceId != null) {
            filters.eq(FlowEvent::getInstanceId, instanceId);
        }
        if (success != null) {
            filters.eq(FlowEvent::getSuccess, success);
        }
        FlexQuery query = new FlexQuery(filters, Orders.ofDesc(FlowEvent::getEventTime));
        Page<FlowEvent> page = eventService.searchEvents(query, Page.of(pageNumber, pageSize));

        Page<FlowEventView> views = Page.of(pageNumber, pageSize);
        views.setTotalCount(page.getTotalCount());
        views.setRows(page.getRows() == null ? java.util.List.of() : page.getRows().stream()
                .map(FlowEventController::toView)
                .toList());
        return ApiResponse.success(views);
    }

    private static FlowEventView toView(FlowEvent event) {
        return new FlowEventView(event.getId(), event.getTriggerType(), event.getSourceModel(),
                event.getSourceRowId(), event.getActorId(), event.getFlowCode(), event.getFlowRevision(),
                event.getInstanceId(), event.getSuccess(), event.getErrorMessage(), event.getFireMethod(),
                event.getEventTime());
    }
}
