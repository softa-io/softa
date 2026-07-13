package io.softa.starter.flow.design;

import java.util.Map;
import io.swagger.v3.oas.annotations.media.Schema;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * Edge definition in the design-time graph document.
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
@Schema(name = "FlowGraphEdge")
public class FlowGraphEdge {

    @Schema(description = "Stable edge id")
    private String id;

    @Schema(description = "Source node id")
    private String source;

    @Schema(description = "Source handle id")
    private String sourceHandle;

    @Schema(description = "Target node id")
    private String target;

    @Schema(description = "Target handle id")
    private String targetHandle;

    @Schema(description = "Edge label displayed on the canvas")
    private String label;

    @Schema(description = "Custom edge component type for the frontend")
    private String type;

    @Schema(description = "Condition expression used by conditional routing")
    private String conditionExpression;

    @Schema(description = "Opaque data bag passed to the frontend edge component")
    private Map<String, Object> data;
}

