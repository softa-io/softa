package io.softa.framework.orm.meta;

import java.util.List;
import java.util.Optional;
import io.swagger.v3.oas.annotations.media.Schema;

/**
 * A declared set of template variables: what a
 * {@link io.softa.framework.base.annotation.TemplateVariableSet} record offers a template.
 *
 * @param name   the name a template stores in {@code variableSetName}
 * @param label  display label
 * @param fields the variables, in declaration order
 */
@Schema(name = "MetaTemplateVariableSet")
public record MetaTemplateVariableSet(
        @Schema(description = "Name a template stores to say it is written against this set") String name,
        @Schema(description = "Display label") String label,
        @Schema(description = "The variables, in declaration order") List<MetaTemplateVariable> fields) {

    /**
     * The variable a dotted path leads to: each part but the last must be a nested set. A list is
     * reached but not entered — its items are read inside a loop, by {@link #itemField}.
     */
    public Optional<MetaTemplateVariable> field(String path) {
        return find(fields, path);
    }

    /** The field {@code path} leads to among the items of the list {@code list}. */
    public Optional<MetaTemplateVariable> itemField(String list, String path) {
        return field(list).filter(MetaTemplateVariable::isList).flatMap(found -> find(found.fields(), path));
    }

    private static Optional<MetaTemplateVariable> find(List<MetaTemplateVariable> fields, String path) {
        if (path == null || path.isBlank()) {
            return Optional.empty();
        }
        List<MetaTemplateVariable> level = fields;
        MetaTemplateVariable found = null;
        for (String part : path.trim().split("\\.")) {
            if (found != null && !found.isNested()) {
                return Optional.empty();
            }
            found = null;
            for (MetaTemplateVariable field : level) {
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
