package io.softa.starter.flow.design;

import java.util.List;
import java.util.Map;
import io.swagger.v3.oas.annotations.media.Schema;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * Full design-time graph document.
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
@Schema(name = "FlowGraphDocument")
public class FlowGraphDocument {

    @Schema(description = "Graph nodes")
    private List<FlowGraphNode> nodes;

    @Schema(description = "Graph edges")
    private List<FlowGraphEdge> edges;

    @Schema(description = "Canvas viewport")
    private FlowGraphViewport viewport;

    @Schema(description = "Graph metadata")
    private Map<String, Object> metadata;
}

