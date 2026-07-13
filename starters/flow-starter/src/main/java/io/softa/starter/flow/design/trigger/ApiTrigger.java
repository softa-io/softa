package io.softa.starter.flow.design.trigger;

import io.swagger.v3.oas.annotations.media.Schema;

/**
 * Trigger fired explicitly via the {@code POST /flow/runtime/trigger} REST endpoint.
 */
@Schema(name = "ApiTrigger")
public record ApiTrigger(

        @Schema(description = "Human-readable description of when this trigger should be called")
        String description

) implements TriggerSource {}
