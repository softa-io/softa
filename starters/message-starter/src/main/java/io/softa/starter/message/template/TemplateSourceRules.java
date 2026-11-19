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
import io.softa.framework.orm.meta.MetaField;
import io.softa.framework.orm.meta.TemplateVariableField;
import io.softa.framework.orm.meta.TemplateVariableSet;
import io.softa.framework.orm.meta.TemplateVariablesRegistry;

/**
 * Whether what a template asks for ({@link TemplateUsage}) is something its source can give.
 *
 * <p>A template names its source in one of two fields: {@code variablesName}, a declared
 * {@code @TemplateVariables} set that code fills, or {@code modelName}, a model whose record fills
 * it. Against either, a name the source does not have is refused when the template is saved — it
 * could only ever print nothing. A template that names no source is not checked at all: it takes
 * whatever it is sent with, as templates always have.
 */
@Component
public class TemplateSourceRules {

    /** A path through more relations than this is not followed. */
    static final int MAX_SEGMENTS = 8;

    private final TemplateModels models;

    public TemplateSourceRules(TemplateModels models) {
        this.models = models;
    }

    /**
     * Everything wrong with a template's content given its source; empty when nothing is, and when
     * the template names no source.
     *
     * @param variablesName the declared set the template is written against, or blank
     * @param modelName     the model whose record fills the template, or blank
     * @param usage         what the template asks for
     */
    public List<String> problems(String variablesName, String modelName, TemplateUsage usage) {
        boolean bySet = StringUtils.hasText(variablesName);
        boolean byModel = StringUtils.hasText(modelName);
        if (bySet && byModel) {
            return List.of("A template is filled either from template variables or from a model's record, "
                    + "not both: clear one of '" + variablesName.trim() + "' and '" + modelName.trim() + "'.");
        }
        if (bySet) {
            return againstSet(variablesName.trim(), usage);
        }
        if (byModel) {
            return againstModel(modelName.trim(), usage);
        }
        return List.of();
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

    // ---- a model --------------------------------------------------------------------------------

    private List<String> againstModel(String model, TemplateUsage usage) {
        if (!models.exists(model)) {
            return List.of("Model '" + model + "' does not exist.");
        }
        List<String> problems = new ArrayList<>();
        Map<String, Set<String>> withheld = models.withheld();
        for (String path : usage.printed()) {
            String problem = readProblem(model, path, withheld);
            if (problem != null) {
                problems.add("'" + path + "': " + problem);
            } else if (FieldType.TO_MANY_TYPES.contains(typeOf(model, path))) {
                problems.add("'" + path + "' is a list: show it with a table that repeats a row for each item.");
            }
        }
        problems.addAll(printedBothWays(usage.printed(), "printed"));
        Set<String> loopLists = new LinkedHashSet<>();
        for (TemplateBlocks.Loop loop : usage.loops()) {
            loopLists.add(loop.list());
            String related = loopProblem(model, loop.list(), withheld, problems);
            if (related == null) {
                continue;
            }
            for (String column : loop.columns()) {
                String problem = readProblem(related, column, withheld);
                if (problem != null) {
                    problems.add("The table over '" + loop.list() + "' shows '" + column + "': " + problem);
                } else if (FieldType.TO_MANY_TYPES.contains(typeOf(related, column))) {
                    problems.add("The table over '" + loop.list() + "' shows '" + column
                            + "', which is itself a list.");
                }
            }
            problems.addAll(printedBothWays(new LinkedHashSet<>(loop.columns()),
                    "shown in the table over '" + loop.list() + "'"));
        }
        for (String list : usage.lists()) {
            if (!loopLists.contains(list)) {
                loopProblem(model, list, withheld, problems);
            }
        }
        Set<String> compared = new LinkedHashSet<>();
        for (TemplateBlocks.Condition condition : usage.conditions()) {
            String problem = readProblem(model, condition.field(), withheld);
            if (problem != null) {
                problems.add("The condition on '" + condition.field() + "': " + problem);
                continue;
            }
            compared.add(condition.field());
            conditionProblem(condition, typeOf(model, condition.field())).ifPresent(problems::add);
        }
        problems.addAll(printedBothWays(compared, "compared"));
        return problems;
    }

    /** Checks a list and reports what is wrong; the model of its items, or null when it cannot be read. */
    private String loopProblem(String model, String list, Map<String, Set<String>> withheld, List<String> problems) {
        String problem = readProblem(model, list, withheld);
        if (problem != null) {
            problems.add("'" + list + "': " + problem);
            return null;
        }
        MetaField field = lastField(model, list);
        if (field == null || !FieldType.TO_MANY_TYPES.contains(field.getFieldType())) {
            problems.add("'" + list + "' is looped over, but it is not a list.");
            return null;
        }
        if (list.indexOf('.') >= 0) {
            problems.add("'" + list + "' is a list reached through another record: a table repeats a list of "
                    + "the template's own model.");
            return null;
        }
        return field.getRelatedModel();
    }

    /** Why a path cannot be read on a model by this caller, or null. */
    private String readProblem(String model, String path, Map<String, Set<String>> withheld) {
        String[] parts = path.split("\\.");
        if (parts.length > MAX_SEGMENTS) {
            return "it follows more than " + MAX_SEGMENTS + " relations.";
        }
        String problem = models.pathProblem(model, path);
        if (problem != null) {
            return problem;
        }
        String current = model;
        for (String part : parts) {
            if (withheld.getOrDefault(current, Set.of()).contains(part)) {
                return "'" + part + "' is a field you have no access to, so a template of yours cannot send it.";
            }
            MetaField field = models.field(current, part);
            current = field == null ? null : field.getRelatedModel();
        }
        return null;
    }

    /** The field a readable path ends on. */
    private MetaField lastField(String model, String path) {
        String current = model;
        MetaField field = null;
        for (String part : path.split("\\.")) {
            field = models.field(current, part);
            current = field == null ? null : field.getRelatedModel();
        }
        return field;
    }

    /** The type of the field a readable path ends on; null when it ends on nothing the model declares. */
    private FieldType typeOf(String model, String path) {
        MetaField field = lastField(model, path);
        return field == null ? null : field.getFieldType();
    }

    /**
     * A relation used both on its own and through one of its fields ({@code departmentId} beside
     * {@code departmentId.name}). The values are handed to the template as nested maps, where one name
     * cannot be both a text and a record — so the template has to pick one.
     */
    private static List<String> printedBothWays(Set<String> paths, String how) {
        List<String> problems = new ArrayList<>();
        for (String path : paths) {
            for (String other : paths) {
                if (other.startsWith(path + ".")) {
                    problems.add("'" + path + "' is " + how + " both on its own and as '" + other
                            + "': use one of its fields in both places, e.g. '" + other + "'.");
                    break;
                }
            }
        }
        return problems;
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
