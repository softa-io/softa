package io.softa.starter.flow.entity;

import java.io.Serial;
import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Data;
import lombok.EqualsAndHashCode;

import io.softa.framework.orm.entity.AuditableModel;

/**
 * FlowEdge Model
 */
@Data
@Schema(name = "FlowEdge")
@EqualsAndHashCode(callSuper = true)
public class FlowEdge extends AuditableModel {

    @Serial
    private static final long serialVersionUID = 1L;

    @Schema(description = "ID")
    private Long id;

    @Schema(description = "Flow ID")
    private Long flowId;

    @Schema(description = "Edge Label")
    private String label;

    @Schema(description = "Source Node ID")
    private Long sourceId;

    @Schema(description = "Target Node ID")
    private Long targetId;
}