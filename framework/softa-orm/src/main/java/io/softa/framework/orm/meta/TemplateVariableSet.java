package io.softa.framework.orm.meta;

import java.util.List;
import java.util.Optional;
import io.swagger.v3.oas.annotations.media.Schema;

/**
 * A declared set of template variables: what a
 * {@link io.softa.framework.base.annotation.TemplateVariables} record offers a template.
 *
 * @param name   the name a template stores in {@code variablesName}
 * @param label  display label
 * @param fields the variables, in declaration order
 */
@Schema(name = "TemplateVariableSet")
public record TemplateVariableSet(
        @Schema(description = "Name a template stores to say it is written against this set") String name,
        @Schema(description = "Display label") String label,
        @Schema(description = "The variables, in declaration order") List<TemplateVariableField> fields) {

    /**
     * The variable a dotted path leads to: each part but the last must be a nested set. A list is
     * reached but not entered — its items are read inside a loop, by {@link #itemField}.
     */
    public Optional<TemplateVariableField> field(String path) {
        return find(fields, path);
    }

    /** The field {@code path} leads to among the items of the list {@code list}. */
    public Optional<TemplateVariableField> itemField(String list, String path) {
        return field(list).filter(TemplateVariableField::isList).flatMap(found -> find(found.fields(), path));
    }

    private static Optional<TemplateVariableField> find(List<TemplateVariableField> fields, String path) {
        if (path == null || path.isBlank()) {
            return Optional.empty();
        }
        List<TemplateVariableField> level = fields;
        TemplateVariableField found = null;
        for (String part : path.trim().split("\\.")) {
            if (found != null && !found.isNested()) {
                return Optional.empty();
            }
            found = null;
            for (TemplateVariableField field : level) {
                if (field.fieldName().equals(part)) {
                    found = field;
                    break;
                }
            }
            if (found == null) {
                return Optional.empty();
            }
            level = found.fields();
        }
        return Optional.of(found);
    }
}
