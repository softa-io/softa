package io.softa.starter.message.template;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;

import io.softa.framework.base.placeholder.TemplateBlocks;
import io.softa.framework.base.placeholder.TemplateCondition;
import io.softa.framework.base.placeholder.TemplateCondition.ValueType;
import io.softa.framework.orm.enums.FieldType;
import io.softa.framework.orm.meta.TemplateVariableField;
import io.softa.framework.orm.meta.TemplateVariableSet;
import io.softa.framework.orm.meta.TemplateVariablesRegistry;

/**
 * Whether what a template asks for ({@link TemplateUsage}) is something its variables can give.
 *
 * <p>A template names the declared {@code @TemplateVariables} set it is written against in
 * {@code variablesName}; code fills it. A name the set does not have is refused when the template is
 * saved — it could only ever print nothing. A template that names no set is not checked at all: it
 * takes whatever it is sent with, as templates always have.
 */
@Component
public class TemplateSourceRules {

    /**
     * Everything wrong with a template's content given its set; empty when nothing is, and when the
     * template names no set.
     *
     * @param variablesName the declared set the template is written against, or blank
     * @param usage         what the template asks for
     */
    public List<String> problems(String variablesName, TemplateUsage usage) {
        return StringUtils.hasText(variablesName) ? againstSet(variablesName.trim(), usage) : List.of();
    }

    /**
     * Why an update may not set {@code variablesName} to {@code wanted}: a template that has a set
     * keeps it. Code fills the template with that set's values, and a send of them through a template
     * moved to another set — or to none — would be refused or print blanks. Empty when the stored row
     * has no set yet, or the value does not change.
     *
     * @param stored the row as stored, or null on create
     * @param wanted the {@code variablesName} the update sends
     */
    public static Optional<String> changedSetProblem(Map<String, Object> stored, Object wanted) {
        Object current = stored == null ? null : stored.get("variablesName");
        String was = current == null ? "" : current.toString().trim();
        String now = wanted == null ? "" : wanted.toString().trim();
        if (was.isEmpty() || was.equals(now)) {
            return Optional.empty();
        }
        return Optional.of("A template's variables cannot be changed once it is saved: it is filled by code with '"
                + was + "'.");
    }

    // ---- a declared set -------------------------------------------------------------------------

    private List<String> againstSet(String name, TemplateUsage usage) {
        Optional<TemplateVariableSet> declared = TemplateVariablesRegistry.find(name);
        if (declared.isEmpty()) {
            return List.of("No template variables are declared under the name '" + name + "'.");
        }
        TemplateVariableSet set = declared.get();
        List<String> problems = new ArrayList<>();
        Set<String> unknown = new LinkedHashSet<>();
        for (String path : usage.printed()) {
            if (set.field(path).isEmpty()) {
                unknown.add(path);
            }
        }
        for (String list : usage.lists()) {
            Optional<TemplateVariableField> field = set.field(list);
            if (field.isEmpty()) {
                unknown.add(list);
            } else if (!field.get().isList()) {
                problems.add("'" + list + "' is looped over, but it is not a list.");
            }
        }
        for (TemplateBlocks.Loop loop : usage.loops()) {
            if (set.field(loop.list()).filter(TemplateVariableField::isList).isEmpty()) {
                continue;   // reported above, as unknown or as not a list
            }
            for (String column : loop.columns()) {
                if (set.itemField(loop.list(), column).isEmpty()) {
                    problems.add("The table over '" + loop.list() + "' shows '" + column
                            + "', which is not a field of its items.");
                }
            }
        }
        for (TemplateBlocks.Condition condition : usage.conditions()) {
            Optional<TemplateVariableField> field = set.field(condition.field());
            if (field.isEmpty()) {
                unknown.add(condition.field());
            } else {
                conditionProblem(condition, field.get().fieldType()).ifPresent(problems::add);
            }
        }
        if (!unknown.isEmpty()) {
            problems.addFirst(unknownMessage(unknown, "'" + name + "' offers: " + namesOf(set) + "."));
        }
        return problems;
    }

    private static String namesOf(TemplateVariableSet set) {
        List<String> names = new ArrayList<>();
        for (TemplateVariableField field : set.fields()) {
            names.add(field.fieldName());
        }
        return String.join(", ", names);
    }

    // ---- conditions -----------------------------------------------------------------------------

    /** Why a condition does not fit a field of {@code type}; the block's own shape is checked on translation. */
    static Optional<String> conditionProblem(TemplateBlocks.Condition condition, FieldType type) {
        Optional<TemplateCondition> known = TemplateCondition.of(condition.op());
        if (known.isEmpty()) {
            return Optional.empty();   // reported once, by the translation
        }
        TemplateCondition op = known.get();
        String subject = "The condition on '" + condition.field() + "'";
        if (op == TemplateCondition.IS_EMPTY || op == TemplateCondition.IS_NOT_EMPTY) {
            return Optional.empty();
        }
        boolean number = FieldType.NUMERIC_TYPES.contains(type);
        boolean text = type == FieldType.STRING || type == FieldType.TEXT || type == FieldType.OPTION;
        boolean bool = type == FieldType.BOOLEAN;
        if (op == TemplateCondition.IS_TRUE || op == TemplateCondition.IS_FALSE) {
            return bool ? Optional.empty()
                    : Optional.of(subject + " asks whether it is true or false, but it is not a yes/no field.");
        }
        if (op.ordering() && !number) {
            return Optional.of(subject + " compares sizes, which only a number allows.");
        }
        if (!number && !text && !bool) {
            return Optional.of(subject + " can only ask whether it is empty: its values cannot be compared.");
        }
        ValueType expected = number ? ValueType.NUMBER : bool ? ValueType.BOOLEAN : ValueType.STRING;
        ValueType given = ValueType.of(condition.valueType()).orElse(null);
        if (given != expected) {
            return Optional.of(subject + " compares against a " + (given == null ? condition.valueType() : given.code())
                    + " value, but the field holds a " + expected.code() + ".");
        }
        String value = condition.value() == null ? "" : condition.value().trim();
        if (number) {
            try {
                new BigDecimal(value);
            } catch (NumberFormatException e) {
                return Optional.of(subject + " compares against '" + value + "', which is not a number.");
            }
        }
        if (bool && !"true".equals(value) && !"false".equals(value)) {
            return Optional.of(subject + " compares against '" + value + "', which is neither true nor false.");
        }
        return Optional.empty();
    }

    private static String unknownMessage(Set<String> unknown, String offers) {
        return (unknown.size() == 1 ? "Unknown variable: " : "Unknown variables: ")
                + String.join(", ", unknown) + ". " + offers;
    }
}
