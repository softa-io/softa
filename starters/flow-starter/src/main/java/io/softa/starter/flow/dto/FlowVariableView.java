package io.softa.starter.flow.dto;

import java.util.List;

import com.fasterxml.jackson.annotation.JsonValue;
import io.swagger.v3.oas.annotations.media.Schema;
import lombok.AllArgsConstructor;
import lombok.Getter;

/**
 * One variable available to a node's expressions — the payload behind the
 * editor's autocomplete for expression inputs and {@code variableRef} pickers.
 */
@Schema(name = "FlowVariableView")
public record FlowVariableView(

        @Schema(description = "Variable name as referenced in expressions")
        String name,

        @Schema(description = "Where the variable comes from")
        Source source,

        @Schema(description = "Human-readable origin, e.g. the producing node's label or the trigger model")
        String sourceLabel,

        @Schema(description = "Value type when derivable — the platform FieldType wire value "
                + "(String/Integer/DateTime/ManyToOne/...) for trigger fields, or a coarse "
                + "Object/List/String for node outputs; null = unknown")
        String type,

        @Schema(description = "Model name when the variable is record-shaped (relation target or data-node model)")
        String modelName,

        @Schema(description = "For a relation of the record a rule reads through: the fields of modelName a rule may "
                + "read through it — its own, and those one more relation on (`departmentId.costCentreId`) — each named "
                + "in the rule by its path `<name>.<field>` (`employeeId.departmentId.costCentreId`); null otherwise. "
                + "Rules only — an expression does not read them")
        List<String> ruleFields
) {

    /** A variable no rule reads through. */
    public FlowVariableView(String name, Source source, String sourceLabel, String type, String modelName) {
        this(name, source, sourceLabel, type, modelName, null);
    }

    @Getter
    @AllArgsConstructor
    public enum Source {
        TRIGGER("Trigger"),
        NODE_OUTPUT("NodeOutput"),
        BUILTIN("Builtin");

        @JsonValue
        private final String type;
    }
}
