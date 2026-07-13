package io.softa.starter.flow.design.trigger;

import java.util.List;

import io.swagger.v3.oas.annotations.media.Schema;

/**
 * Trigger fired when a field value changes.
 * <p>
 * Only valid for {@code FlowScenario.COMPUTE} flows (field onchange computation).
 */
@Schema(name = "FieldChangeTrigger")
public record FieldChangeTrigger(String modelName, List<String> fieldNames) implements TriggerSource {

    public FieldChangeTrigger {
        fieldNames = fieldNames == null ? List.of() : List.copyOf(fieldNames);
    }

    public FieldChangeTrigger() {
        this(null, List.of());
    }
}
