package io.softa.framework.orm.meta;

import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.ParameterizedType;
import java.lang.reflect.RecordComponent;
import java.lang.reflect.Type;
import java.math.BigDecimal;
import java.math.BigInteger;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import io.swagger.v3.oas.annotations.media.Schema;

import io.softa.framework.base.annotation.TemplateVariables;
import io.softa.framework.base.utils.Assert;
import io.softa.framework.base.utils.StringTools;
import io.softa.framework.orm.enums.FieldType;

/**
 * The sets of template variables the application declares with {@link TemplateVariables}, by name.
 *
 * <p>Held in memory only: a set changes with the code that fills it, so there is nothing to keep in
 * step in a table. {@link TemplateVariablesScanner} fills the registry once at startup; a set that
 * cannot be described — a duplicate name, a class that is not a record, a component of a type a
 * template cannot print — stops the application there, naming the class.
 */
public final class TemplateVariablesRegistry {

    /** A nested set deeper than this is a set that contains itself. */
    private static final int MAX_DEPTH = 8;

    private static volatile Map<String, Entry> SETS = Map.of();

    private TemplateVariablesRegistry() {
    }

    private record Entry(Class<?> type, TemplateVariableSet set) {
    }

    /**
     * Replaces the registry with the sets {@code classes} declare.
     *
     * @throws IllegalArgumentException naming the first class that cannot be registered
     */
    public static void register(Collection<Class<?>> classes) {
        Map<String, Entry> sets = new LinkedHashMap<>();
        // By class name, so the class named in a duplicate report does not depend on scan order.
        List<Class<?>> ordered = new ArrayList<>(classes);
        ordered.sort(Comparator.comparing(Class::getName));
        for (Class<?> type : ordered) {
            TemplateVariables declared = type.getAnnotation(TemplateVariables.class);
            Assert.notNull(declared, "{0} carries no @TemplateVariables.", type.getName());
            Assert.isTrue(type.isRecord(),
                    "@TemplateVariables on {0}: a set of template variables is declared on a record.", type.getName());
            String name = declared.name() == null ? "" : declared.name().trim();
            Assert.notBlank(name, "@TemplateVariables on {0} declares no name.", type.getName());
            Entry existing = sets.get(name);
            Assert.isTrue(existing == null,
                    "Template variables ''{0}'' are declared twice: by {1} and by {2}. A name belongs to one set.",
                    name, existing == null ? null : existing.type().getName(), type.getName());
            String label = declared.label().isBlank() ? StringTools.humanize(lastSegment(name)) : declared.label();
            sets.put(name, new Entry(type, new TemplateVariableSet(name, label, fieldsOf(type, type, 1))));
        }
        SETS = Map.copyOf(sets);
    }

    /** The set of that name, when one is declared. */
    public static Optional<TemplateVariableSet> find(String name) {
        if (name == null || name.isBlank()) {
            return Optional.empty();
        }
        return Optional.ofNullable(SETS.get(name.trim())).map(Entry::set);
    }

    /** Every declared set, by name. */
    public static List<TemplateVariableSet> all() {
        return SETS.values().stream().map(Entry::set)
                .sorted(Comparator.comparing(TemplateVariableSet::name)).toList();
    }

    /** The names of every declared set. */
    public static Set<String> names() {
        return SETS.keySet();
    }

    /** The name of the set an object belongs to, or null when its class declares none. */
    public static String nameOf(Object variables) {
        if (variables == null) {
            return null;
        }
        TemplateVariables declared = variables.getClass().getAnnotation(TemplateVariables.class);
        return declared == null ? null : declared.name().trim();
    }

    /**
     * The values of a variables object as the map a template is rendered from: one entry per
     * component, a nested record as a map of its own, a list of records as a list of maps. A missing
     * value becomes the empty string — in a subject a name with no value would otherwise stay
     * visible as {@code {{ name }}}.
     *
     * @throws IllegalArgumentException when {@code variables} is not a {@link TemplateVariables} record
     */
    public static Map<String, Object> toMap(Object variables) {
        requireDeclared(variables);
        return valuesOf(variables, true);
    }

    /**
     * {@link #toMap} with the top-level components that have no value left out instead of emptied.
     * For a channel whose provider is handed the variables themselves — an SMS provider fills its
     * own registered template from them, some by position — where an extra empty one is one too many.
     */
    public static Map<String, Object> toMapOmittingMissing(Object variables) {
        requireDeclared(variables);
        return valuesOf(variables, false);
    }

    private static void requireDeclared(Object variables) {
        Assert.notNull(variables, "Template variables cannot be null.");
        Assert.isTrue(variables.getClass().isRecord() && nameOf(variables) != null,
                "{0} is not a @TemplateVariables record.", variables.getClass().getName());
    }

    private static Map<String, Object> valuesOf(Object record, boolean keepMissing) {
        Map<String, Object> values = new LinkedHashMap<>();
        for (RecordComponent component : record.getClass().getRecordComponents()) {
            Object value = read(component, record);
            if (value != null) {
                values.put(component.getName(), valueOf(value));
            } else if (keepMissing) {
                values.put(component.getName(), missing(component.getType()));
            }
        }
        return values;
    }

    /**
     * What a component left null is sent as: an empty list for a list — a loop over it repeats nothing,
     * where over {@code ""} the engine refuses the template — an empty set of values for a nested set,
     * and an empty text for a single value.
     */
    private static Object missing(Class<?> type) {
        if (Collection.class.isAssignableFrom(type)) {
            return List.of();
        }
        return type.isRecord() ? Map.of() : "";
    }

    private static Object valueOf(Object value) {
        if (value == null) {
            return "";
        }
        if (value.getClass().isRecord()) {
            return valuesOf(value, true);
        }
        if (value instanceof Collection<?> items) {
            List<Object> list = new ArrayList<>(items.size());
            for (Object item : items) {
                list.add(valueOf(item));
            }
            return list;
        }
        return value;
    }

    private static Object read(RecordComponent component, Object record) {
        try {
            return component.getAccessor().invoke(record);
        } catch (IllegalAccessException | InvocationTargetException e) {
            throw new IllegalStateException("Cannot read " + record.getClass().getName() + "."
                    + component.getName() + ". A @TemplateVariables record and the records it nests are public.", e);
        }
    }

    private static List<TemplateVariableField> fieldsOf(Class<?> record, Class<?> root, int depth) {
        Assert.isTrue(depth <= MAX_DEPTH,
                "@TemplateVariables on {0}: {1} nests deeper than {2} levels, or contains itself.",
                root.getName(), record.getName(), MAX_DEPTH);
        List<TemplateVariableField> fields = new ArrayList<>();
        for (RecordComponent component : record.getRecordComponents()) {
            fields.add(fieldOf(component, root, depth));
        }
        return List.copyOf(fields);
    }

    private static TemplateVariableField fieldOf(RecordComponent component, Class<?> root, int depth) {
        Schema schema = schemaOf(component);
        String name = component.getName();
        String label = schema == null || schema.title().isBlank() ? StringTools.humanize(name) : schema.title();
        String description = schema == null ? "" : schema.description();
        String example = schema == null ? "" : schema.example();
        Class<?> type = component.getType();
        FieldType scalar = scalarTypeOf(type);
        if (scalar != null) {
            return new TemplateVariableField(name, label, scalar, description, example, List.of());
        }
        if (type.isRecord()) {
            return new TemplateVariableField(name, label, FieldType.DTO, description, example,
                    fieldsOf(type, root, depth + 1));
        }
        if (Collection.class.isAssignableFrom(type)) {
            Class<?> item = itemTypeOf(component.getGenericType());
            Assert.isTrue(item != null && item.isRecord(),
                    "@TemplateVariables on {0}: {1} is a list of {2}. A list holds records, so a template can "
                            + "name the fields of each item.",
                    root.getName(), name, item == null ? "an unknown type" : item.getName());
            return new TemplateVariableField(name, label, FieldType.ONE_TO_MANY, description, example,
                    fieldsOf(item, root, depth + 1));
        }
        throw new IllegalArgumentException("@TemplateVariables on " + root.getName() + ": " + name + " is a "
                + type.getName() + ". A variable is text, a number, a boolean, a date or time, an enum, "
                + "a record, or a list of records.");
    }

    /** The component's {@code @Schema}: it lands on the accessor, or on the field behind it. */
    private static Schema schemaOf(RecordComponent component) {
        Schema schema = component.getAccessor().getAnnotation(Schema.class);
        if (schema != null) {
            return schema;
        }
        try {
            return component.getDeclaringRecord().getDeclaredField(component.getName()).getAnnotation(Schema.class);
        } catch (NoSuchFieldException e) {
            return null;
        }
    }

    private static FieldType scalarTypeOf(Class<?> type) {
        if (CharSequence.class.isAssignableFrom(type) || type == char.class || type == Character.class) {
            return FieldType.STRING;
        }
        if (type == int.class || type == Integer.class || type == short.class || type == Short.class
                || type == byte.class || type == Byte.class) {
            return FieldType.INTEGER;
        }
        if (type == long.class || type == Long.class) {
            return FieldType.LONG;
        }
        if (type == double.class || type == Double.class || type == float.class || type == Float.class) {
            return FieldType.DOUBLE;
        }
        if (type == BigDecimal.class || type == BigInteger.class) {
            return FieldType.BIG_DECIMAL;
        }
        if (type == boolean.class || type == Boolean.class) {
            return FieldType.BOOLEAN;
        }
        if (type == LocalDate.class) {
            return FieldType.DATE;
        }
        if (type == LocalDateTime.class) {
            return FieldType.DATE_TIME;
        }
        if (type == LocalTime.class) {
            return FieldType.TIME;
        }
        if (type.isEnum()) {
            return FieldType.OPTION;
        }
        return null;
    }

    private static Class<?> itemTypeOf(Type generic) {
        if (generic instanceof ParameterizedType parameterized && parameterized.getActualTypeArguments().length == 1
                && parameterized.getActualTypeArguments()[0] instanceof Class<?> item) {
            return item;
        }
        return null;
    }

    private static String lastSegment(String name) {
        int dot = name.lastIndexOf('.');
        return dot < 0 ? name : name.substring(dot + 1);
    }
}
