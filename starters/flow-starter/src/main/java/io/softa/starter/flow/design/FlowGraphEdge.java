package io.softa.starter.flow.design;

import java.util.Map;
import io.swagger.v3.oas.annotations.media.Schema;

import io.softa.framework.orm.domain.Filters;
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

    @Schema(description = "Rule condition over the fields of the flow's record — the platform Filters JSON, "
            + "evaluated like a field condition. Exclusive with conditionExpression")
    private Filters condition;

    @Schema(description = "AviatorScript condition for what a rule cannot say — arithmetic, functions, "
            + "node outputs. Exclusive with condition")
    private String conditionExpression;

    @Schema(description = "Where the edge stands among its node's conditional edges, 1 first: a node that is not a "
            + "gateway takes the first whose condition holds. Edges without one come after those with one, in drawing "
            + "order, and the edge without a condition — the default — is always last, so it takes none. An inclusive "
            + "gateway and a parallel fork do not read it: they take every edge that holds, or every edge")
    private Integer priority;

    @Schema(description = "Opaque data bag passed to the frontend edge component")
    private Map<String, Object> data;

    /** Whether the edge is taken only when a condition holds — either kind; one without is a default edge. */
    public boolean hasCondition() {
        return !Filters.isEmpty(condition) || (conditionExpression != null && !conditionExpression.isBlank());
    }
}

