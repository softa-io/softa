package io.softa.starter.flow.runtime.api;

import java.util.List;
import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotEmpty;
import lombok.Data;
import lombok.EqualsAndHashCode;

/**
 * Base request carrying multiple target actors on a pending approval node.
 */
@Data
@EqualsAndHashCode(callSuper = true)
public abstract class AbstractFlowNodeBatchTargetActorsRequest extends AbstractFlowNodeRequest {

    @NotEmpty(message = "targetActorIds must not be empty")
    @Schema(description = "Ordered target actor ids")
    private List<String> targetActorIds;
}

