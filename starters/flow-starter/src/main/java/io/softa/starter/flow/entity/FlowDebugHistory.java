package io.softa.starter.flow.entity;

import java.io.Serial;
import java.time.LocalDateTime;
import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Data;
import lombok.EqualsAndHashCode;
import tools.jackson.databind.JsonNode;

import io.softa.framework.orm.entity.AuditableModel;
import io.softa.starter.flow.enums.FlowStatus;
import io.softa.starter.flow.enums.FlowType;

/**
 * FlowDebugHistory Model
 */
@Data
@Schema(name = "FlowDebugHistory")
@EqualsAndHashCode(callSuper = true)
public class FlowDebugHistory extends AuditableModel {

    @Serial
    private static final long serialVersionUID = 1L;

    @Schema(description = "ID")
    private Long id;

    @Schema(description = "Flow ID")
    private Long flowId;

    @Schema(description = "Flow Type")
    private FlowType flowType;

    @Schema(description = "Flow Status")
    private FlowStatus flowStatus;

    @Schema(description = "Main Flow ID")
    private Long mainFlowId;

    @Schema(description = "Parent Flow ID")
    private Long parentFlowId;

    @Schema(description = "Start Time")
    private LocalDateTime startTime;

    @Schema(description = "End Time")
    private LocalDateTime endTime;

    @Schema(description = "Event Message")
    private JsonNode eventMessage;

    @Schema(description = "Node Trace")
    private JsonNode nodeTrace;
}