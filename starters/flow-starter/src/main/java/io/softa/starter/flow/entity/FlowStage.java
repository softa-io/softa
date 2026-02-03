package io.softa.starter.flow.entity;

import io.softa.framework.orm.entity.AuditableModel;
import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Data;
import lombok.EqualsAndHashCode;

import java.io.Serial;

/**
 * FlowStage Model
 */
@Data
@Schema(name = "FlowStage")
@EqualsAndHashCode(callSuper = true)
public class FlowStage extends AuditableModel {

    @Serial
    private static final long serialVersionUID = 1L;

    @Schema(description = "ID")
    private String id;

    @Schema(description = "Flow ID")
    private String flowId;

    @Schema(description = "Stage Name")
    private String name;

    @Schema(description = "Stage Description")
    private String description;
}