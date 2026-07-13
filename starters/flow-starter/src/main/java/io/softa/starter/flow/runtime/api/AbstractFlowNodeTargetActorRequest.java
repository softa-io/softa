package io.softa.starter.flow.runtime.api;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotBlank;
import lombok.Data;
import lombok.EqualsAndHashCode;

/**
 * Base request carrying one target actor on a pending approval node.
 */
@Data
@EqualsAndHashCode(callSuper = true)
public abstract class AbstractFlowNodeTargetActorRequest extends AbstractFlowNodeRequest {

    @NotBlank(message = "targetActorId must not be blank")
    @Schema(description = "Target actor id receiving the action effect")
    private String targetActorId;
}

