package io.softa.starter.flow.runtime.bundle;

import java.util.Map;
import io.swagger.v3.oas.annotations.media.Schema;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * Runtime transition compiled from a design-time graph edge.
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
@Schema(name = "CompiledFlowTransition")
public class CompiledFlowTransition {

    @Schema(description = "Edge id")
    private String edgeId;

    @Schema(description = "Source node id")
    private String source;

    @Schema(description = "Source handle")
    private String sourceHandle;

    @Schema(description = "Target node id")
    private String target;

    @Schema(description = "Target handle")
    private String targetHandle;

    @Schema(description = "Condition expression")
    private String conditionExpression;

    @Schema(description = "Transition data")
    private Map<String, Object> data;
}

