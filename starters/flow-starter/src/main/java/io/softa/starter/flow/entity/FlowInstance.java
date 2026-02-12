package io.softa.starter.flow.entity;

import java.io.Serial;
import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Data;
import lombok.EqualsAndHashCode;

import io.softa.framework.orm.entity.AuditableModel;
import io.softa.starter.flow.enums.FlowStatus;
import io.softa.starter.flow.enums.FlowType;

/**
 * FlowInstance Model
 */
@Data
@Schema(name = "FlowInstance")
@EqualsAndHashCode(callSuper = true)
public class FlowInstance extends AuditableModel {

    @Serial
    private static final long serialVersionUID = 1L;

    @Schema(description = "ID")
    private Long id;

    @Schema(description = "Main Model")
    private String modelName;

    @Schema(description = "Row Data ID")
    private String rowId;

    @Schema(description = "Flow ID")
    private Long flowId;

    @Schema(description = "Flow Type")
    private FlowType flowType;

    @Schema(description = "Trigger ID")
    private Long triggerId;

    @Schema(description = "Current Node ID")
    private Long currentNodeId;

    @Schema(description = "Current Status")
    private FlowStatus currentStatus;
}