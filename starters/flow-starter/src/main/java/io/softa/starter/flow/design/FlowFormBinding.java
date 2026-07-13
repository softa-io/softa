package io.softa.starter.flow.design;

import io.swagger.v3.oas.annotations.media.Schema;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import io.softa.starter.flow.enums.FlowFormUsage;

/**
 * Form contract bound to a flow definition.
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
@Schema(name = "FlowFormBinding")
public class FlowFormBinding {

    @Schema(description = "Form code")
    private String formCode;

    @Schema(description = "Usage within the flow")
    private FlowFormUsage usage;

    @Schema(description = "Bound node id when usage is task-scoped")
    private String nodeId;
}

