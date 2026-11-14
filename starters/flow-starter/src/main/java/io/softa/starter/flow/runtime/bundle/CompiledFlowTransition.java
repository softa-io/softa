package io.softa.starter.flow.runtime.bundle;

import io.swagger.v3.oas.annotations.media.Schema;

import io.softa.framework.orm.domain.Filters;
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

    @Schema(description = "Target node id")
    private String target;

    @Schema(description = "Rule condition over the fields of the flow's record (Filters); exclusive with conditionExpression")
    private Filters condition;

    @Schema(description = "Condition expression")
    private String conditionExpression;

    /** Whether the transition is taken only when a condition holds — either kind; one without is a default. */
    public boolean hasCondition() {
        return !Filters.isEmpty(condition) || (conditionExpression != null && !conditionExpression.isBlank());
    }
}

