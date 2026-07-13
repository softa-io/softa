package io.softa.starter.flow.design.trigger;

import java.util.List;

import io.swagger.v3.oas.annotations.media.Schema;

/**
 * Trigger fired when a tracked entity changes.
 * <p>
 * Consumed by {@code ChangeLogFlowConsumer} via Pulsar change-log messages.
 *
 * <p>{@code events} narrows the trigger to specific change types; an empty/unset
 * list matches every change. (Before-vs-after-commit phase is not a trigger
 * attribute — it is decided by the flow's sync/async dispatch path, so the former
 * {@code phase} field was removed as redundant.)
 */
@Schema(name = "EntityChangeTrigger")
public record EntityChangeTrigger(

        @Schema(description = "Model name of the entity to watch")
        String modelName,

        @Schema(description = "Change events that activate this trigger (CREATE, UPDATE, DELETE); empty = all")
        List<ChangeEvent> events,

        @Schema(description = "Optional AviatorScript expression; trigger fires only when true")
        String conditionExpression

) implements TriggerSource {}
