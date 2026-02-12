package io.softa.starter.flow.entity;

import java.io.Serial;
import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Data;
import lombok.EqualsAndHashCode;

import io.softa.framework.orm.entity.AuditableModel;

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
    private Long id;

    @Schema(description = "Flow ID")
    private Long flowId;

    @Schema(description = "Stage Name")
    private String name;

    @Schema(description = "Stage Description")
    private String description;
}