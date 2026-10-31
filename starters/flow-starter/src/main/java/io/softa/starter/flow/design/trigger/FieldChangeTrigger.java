package io.softa.starter.flow.design.trigger;

import java.util.List;

import io.swagger.v3.oas.annotations.media.Schema;

/**
 * Trigger fired when a field of a record of the flow's model changes — the model is the flow's
 * ({@code DesignFlowDefinition#getModelName()}), required.
 * <p>
 * Only valid for {@code FlowScenario.COMPUTE} flows (field onchange computation).
 */
@Schema(name = "FieldChangeTrigger")
public record FieldChangeTrigger(List<String> fieldNames) implements TriggerSource {

    public FieldChangeTrigger {
        fieldNames = fieldNames == null ? List.of() : List.copyOf(fieldNames);
    }

    public FieldChangeTrigger() {
        this(List.of());
    }

    @Override
    public boolean onRecord() {
        return true;
    }
}
