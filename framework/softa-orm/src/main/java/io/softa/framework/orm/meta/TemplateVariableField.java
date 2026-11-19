package io.softa.framework.orm.meta;

import java.util.List;
import com.fasterxml.jackson.annotation.JsonIgnore;
import io.swagger.v3.oas.annotations.media.Schema;

import io.softa.framework.orm.enums.FieldType;

/**
 * One variable of a {@link TemplateVariableSet}, described the way a model field is: name, label
 * and type, so the picker that offers a model's fields can offer these unchanged.
 *
 * @param fieldName   the name a template refers to it by
 * @param label       what the picker shows
 * @param fieldType   the model field type the value corresponds to; {@link FieldType#DTO} for a
 *                    nested set (read as {@code fieldName.inner}) and {@link FieldType#ONE_TO_MANY}
 *                    for a list of them (a loop)
 * @param description what the value is, when the label does not say enough; may be empty
 * @param example     a sample value, for previewing a template; may be empty
 * @param fields      the fields of a nested set or of a list's items; empty for a plain value
 */
@Schema(name = "TemplateVariableField")
public record TemplateVariableField(
        @Schema(description = "Name a template refers to the variable by") String fieldName,
        @Schema(description = "Display label") String label,
        @Schema(description = "Field type; DTO for a nested set, OneToMany for a list") FieldType fieldType,
        @Schema(description = "What the value is") String description,
        @Schema(description = "Sample value") String example,
        @Schema(description = "Fields of a nested set or of a list's items") List<TemplateVariableField> fields) {

    /** Whether the value is a list a template loops over. */
    @JsonIgnore
    public boolean isList() {
        return fieldType == FieldType.ONE_TO_MANY;
    }

    /** Whether the value is a nested set whose fields are read with a dot. */
    @JsonIgnore
    public boolean isNested() {
        return fieldType == FieldType.DTO;
    }
}
