package io.softa.starter.flow.enums;

import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonValue;
import io.softa.framework.base.annotation.OptionItem;
import io.softa.framework.base.annotation.OptionSet;
import lombok.AllArgsConstructor;
import lombok.Getter;

/**
 * Execution scenario of a flow definition.
 * <p>
 * Replaces the former {@code FlowKind × FlowPurpose} two-dimensional matrix with a
 * single, self-contained discriminator. All three values are orthogonal — no
 * compatibility matrix is required.
 *
 * <ul>
 *   <li>{@link #PROCESS} — stateful, persisted instances; covers both automation
 *       flows and human-approval flows. Whether the flow is human-centric is derived
 *       at compile time from graph contents and recorded in
 *       {@code CompiledFlowCapabilitySummary.hasApprovalNode}.</li>
 *   <li>{@link #VALIDATION} — stateless, synchronous, returns a list of validation
 *       errors. No instance is persisted.</li>
 *   <li>{@link #COMPUTE} — stateless, synchronous, returns variable diffs.
 *       Replaces the former {@code FieldOnchange} purpose.</li>
 * </ul>
 */
@Getter
@AllArgsConstructor
@OptionSet
public enum FlowScenario {

    @OptionItem(label = "Process Flow")
    PROCESS("Process"),
    @OptionItem(label = "Validation Flow")
    VALIDATION("Validation"),
    @OptionItem(label = "Compute Flow")
    COMPUTE("Compute"),
    ;

    @JsonValue
    private final String type;

    @JsonCreator
    public static FlowScenario fromValue(String value) {
        for (FlowScenario s : values()) {
            if (s.type.equalsIgnoreCase(value) || s.name().equalsIgnoreCase(value)) {
                return s;
            }
        }
        throw new IllegalArgumentException("Unsupported flow scenario: " + value);
    }
}
