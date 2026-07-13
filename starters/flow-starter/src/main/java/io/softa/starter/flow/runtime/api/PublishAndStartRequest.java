package io.softa.starter.flow.runtime.api;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotNull;
import lombok.Data;
import lombok.EqualsAndHashCode;

import io.softa.starter.flow.design.DesignFlowDefinition;

/**
 * Request to publish a design definition and immediately start it.
 */
@Data
@EqualsAndHashCode(callSuper = true)
@Schema(name = "PublishAndStartRequest")
public class PublishAndStartRequest extends AbstractFlowLaunchContextRequest {

    @NotNull(message = "designId is required")
    @Schema(description = "Flow design id (required)")
    private Long designId;

    @NotNull(message = "definition is required")
    @Schema(description = "Design-time flow definition")
    private DesignFlowDefinition definition;
}

