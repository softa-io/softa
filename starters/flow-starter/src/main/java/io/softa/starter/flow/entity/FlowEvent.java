package io.softa.starter.flow.entity;

import io.softa.framework.orm.entity.AuditableModel;
import io.softa.starter.flow.enums.TriggerEventType;
import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Data;
import lombok.EqualsAndHashCode;

import java.io.Serial;

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
    private String id;

    @Schema(description = "Flow ID")
    private String flowId;

    @Schema(description = "Node ID")
    private String nodeId;

    @Schema(description = "Trigger ID")
    private String triggerId;

    @Schema(description = "Trigger Type")
    private TriggerEventType triggerType;

    @Schema(description = "Source Model")
    private String sourceModel;

    @Schema(description = "Row Data ID")
    private String rowId;
}