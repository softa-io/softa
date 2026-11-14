package io.softa.starter.flow.design.trigger;

import java.util.List;

import io.swagger.v3.oas.annotations.media.Schema;

import io.softa.framework.orm.domain.Filters;

/**
 * Trigger fired when a record of {@code modelName} changes.
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

        @Schema(description = "The model whose records' changes start the flow; required")
        String modelName,

        @Schema(description = "Change events that activate this trigger (CREATE, UPDATE, DELETE); empty = all")
        List<ChangeEvent> events,

        @Schema(description = "Optional AviatorScript expression; trigger fires only when true. Exclusive with condition")
        String conditionExpression,

        @Schema(description = "Optional rule over the changed record's fields (platform Filters), judged like an edge's "
                + "rule condition; trigger fires only when it holds. Exclusive with conditionExpression")
        Filters condition

) implements TriggerSource {

    /** A trigger on these events of {@code modelName}, decided by an expression (or by nothing). */
    public EntityChangeTrigger(String modelName, List<ChangeEvent> events, String conditionExpression) {
        this(modelName, events, conditionExpression, null);
    }

    @Override
    public boolean onRecord() {
        return true;
    }
}
