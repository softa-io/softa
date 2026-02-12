package io.softa.starter.flow.entity;

import java.io.Serial;
import java.util.List;
import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Data;
import lombok.EqualsAndHashCode;
import tools.jackson.databind.JsonNode;

import io.softa.framework.orm.entity.AuditableModel;
import io.softa.starter.flow.enums.FlowNodeType;

/**
 * FlowNode Model
 */
@Data
@Schema(name = "FlowNode")
@EqualsAndHashCode(callSuper = true)
public class FlowNode extends AuditableModel {

    @Serial
    private static final long serialVersionUID = 1L;

    @Schema(description = "ID")
    private Long id;

    @Schema(description = "Node Name")
    private String name;

    @Schema(description = "Flow ID")
    private Long flowId;

    @Schema(description = "Stage ID")
    private Long stageId;

    @Schema(description = "Node Type")
    private FlowNodeType nodeType;

    @Schema(description = "Sequence")
    private Integer sequence;

    @Schema(description = "Parent Node ID")
    private Long parentId;

    @Schema(description = "Child Nodes")
    private List<FlowNode> childNodes;

    @Schema(description = "Node Execute Condition")
    private String nodeCondition;

    @Schema(description = "Node Params")
    private JsonNode nodeParams;

    @Schema(description = "Exception Policy")
    private JsonNode exceptionPolicy;

    @Schema(description = "Position")
    private JsonNode position;

    @Schema(description = "Description")
    private String description;
}