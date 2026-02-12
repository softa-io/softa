package io.softa.starter.flow.entity;

import java.io.Serial;
import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Data;
import lombok.EqualsAndHashCode;

import io.softa.framework.orm.entity.AuditableModel;
import io.softa.starter.flow.enums.TriggerEventType;

/**
 * FlowEvent Model
 */
@Data
@Schema(name = "FlowEvent")
@EqualsAndHashCode(callSuper = true)
public class FlowEvent extends AuditableModel {

    @Serial
    private static final long serialVersionUID = 1L;

    @Schema(description = "ID")
    private Long id;

    @Schema(description = "Flow ID")
    private Long flowId;

    @Schema(description = "Node ID")
    private Long nodeId;

    @Schema(description = "Trigger ID")
    private Long triggerId;

    @Schema(description = "Trigger Type")
    private TriggerEventType triggerType;

    @Schema(description = "Source Model")
    private String sourceModel;

    @Schema(description = "Row Data ID")
    private String rowId;
}