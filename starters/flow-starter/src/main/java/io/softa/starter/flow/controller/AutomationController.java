package io.softa.starter.flow.controller;

import io.softa.framework.base.utils.Assert;
import io.softa.framework.orm.annotation.DataMask;
import io.softa.framework.web.response.ApiResponse;
import io.softa.starter.flow.FlowAutomation;
import io.softa.starter.flow.message.dto.FlowEventMessage;
import io.softa.starter.flow.dto.FlowEventDTO;
import io.softa.starter.flow.dto.TriggerEventDTO;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.core.env.Environment;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.Arrays;
import java.util.Map;

/**
 * Flow automation controller
 */
@Tag(name = "Flow Automation")
@RestController
@RequestMapping("/automation")
public class AutomationController {

    @Autowired
    private Environment env;

    @Autowired
    private FlowAutomation automation;

    @Operation(summary = "API Event", description = "Trigger the flow by API event. ")
    @PostMapping("/apiEvent")
    @DataMask
    public ApiResponse<Object> apiEvent(@RequestBody @Valid TriggerEventDTO triggerEventDTO) {
        return ApiResponse.success(automation.apiEvent(triggerEventDTO));
    }

    /**
     * Handles onchange events that cause changes in other field values.
     *
     * @param triggerEventDTO The event parameters that include current data and field changes.
     * @return An ApiResponse containing a Map of other field values affected by the change.
     */
    @Operation(summary = "Onchange Event",
            description = "Pass the current data and return a Map of field value changes that affect other fields.")
    @PostMapping("/onchange")
    @DataMask
    public ApiResponse<Map<String, Object>> onchange(@RequestBody @Valid TriggerEventDTO triggerEventDTO) {
        return ApiResponse.success(automation.onchangeEvent(triggerEventDTO));
    }

    /**
     * Simulates an event message for flow triggering.
     * This method is used for testing in non-production environments, such as dev, test and uat.
     *
     * @param flowEventDTO The event parameters used to simulate the flow trigger.
     * @return An ApiResponse containing the result of the simulated flow trigger.
     */
    @Operation(summary = "Simulate Event Message", description = """
            Simulate flow triggering by passing a FlowEventDTO, suitable for scenarios such as ChangeLog, Cron, etc.
            For non-production environment testing only.""")
    @PostMapping("/simulateEvent")
    public ApiResponse<Object> simulateEvent(@RequestBody FlowEventDTO flowEventDTO) {
        String[] profiles = env.getActiveProfiles();
        Assert.notTrue(Arrays.asList(profiles).contains("prod"),
                "This API is only open to non-production environments!");
        FlowEventMessage message = new FlowEventMessage();
        message.setFlowId(flowEventDTO.getFlowId());
        message.setFlowNodeId(flowEventDTO.getFlowNodeId());
        message.setSync(true);
        message.setRollbackOnFail(flowEventDTO.getRollbackOnFail());
        message.setTriggerId(flowEventDTO.getTriggerId());
        message.setSourceModel(flowEventDTO.getSourceModel());
        message.setSourceRowId(flowEventDTO.getSourceRowId());
        message.setTriggerParams(flowEventDTO.getTriggerParams());
        return ApiResponse.success(automation.triggerFlow(message));
    }
}
