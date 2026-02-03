package io.softa.starter.flow.entity;

import io.softa.framework.orm.entity.AuditableModel;
import io.softa.starter.flow.enums.TriggerEventType;
import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Data;
import lombok.EqualsAndHashCode;

import java.io.Serial;
import java.util.List;

/**
 * FlowTrigger Model
 */
@Data
@Schema(name = "FlowTrigger")
@EqualsAndHashCode(callSuper = true)
public class FlowTrigger extends AuditableModel {

    @Serial
    private static final long serialVersionUID = 1L;

    @Schema(description = "ID")
    private String id;

    @Schema(description = "Trigger Name")
    private String name;

    @Schema(description = "Triggered Flow")
    private String flowId;

    @Schema(description = "Trigger Event Type")
    private TriggerEventType eventType;

    @Schema(description = "Source Model")
    private String sourceModel;

    @Schema(description = "Source Fields")
    private List<String> sourceFields;

    @Schema(description = "Trigger Condition")
    private String triggerCondition;

    @Schema(description = "Cron Job ID")
    private Long cronId;

    @Schema(description = "Active")
    private Boolean active;
}