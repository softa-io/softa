package io.softa.starter.flow.design.trigger;

import io.swagger.v3.oas.annotations.media.Schema;

/**
 * Trigger of a flow started explicitly by its id, through {@code POST /flow/instances/start}; no event starts it.
 */
@Schema(name = "ApiTrigger")
public record ApiTrigger(

        @Schema(description = "Human-readable description of when this trigger should be called")
        String description

) implements TriggerSource {}
