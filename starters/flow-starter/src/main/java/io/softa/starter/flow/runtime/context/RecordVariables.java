package io.softa.starter.flow.runtime.context;

import java.io.Serializable;
import java.math.BigDecimal;
import java.time.temporal.Temporal;
import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;
import java.util.stream.Collectors;
import java.util.stream.Stream;

import org.jspecify.annotations.Nullable;

import io.softa.framework.base.context.ContextUtils;
import io.softa.framework.orm.constant.ModelConstant;
import io.softa.framework.orm.domain.Filters;
import io.softa.framework.orm.domain.FlexQuery;
import io.softa.framework.orm.enums.FieldType;
import io.softa.framework.orm.meta.MetaField;
import io.softa.framework.orm.meta.ModelManager;
import io.softa.framework.orm.service.ModelService;
import io.softa.framework.orm.utils.IdUtils;
import io.softa.framework.orm.vo.ModelReference;
import io.softa.starter.flow.entity.ApprovalModel;

/**
 * The variables a record brings to the flow it starts: every field a route can compare — its own columns of a plain
 * type, a date or time as ISO text — under the field's name, and the fields up to two hops on through relations that
 * its rules read, under the path: {@code employeeId.departmentId}, {@code employeeId.departmentId.costCentreId}. The
 * model's handler adds what it works out on top
 * ({@code ModelApprovalHandler#submission}). One selection serves the instance (at submission, and again after an
 * approver edits the record), the designer's variable list and the route-rule check, so a field the designer offers is
 * a field the instance carries.
 *
 * <p>A path's value is read when the flow starts, from the record the relation points at then, and kept with the
 * record's own values: every route of the instance decides on what the record and its related records said at
 * submission — an employee moving department halfway through an approval does not reroute it, and the path the
 * submitter was shown is the one it takes. Only the paths the flow's rules name are read
 * ({@link #pathsIn}), not every field of every related record.
 *
 * <p>A field declared sensitive — encrypted, or masked when read — is never routed, on the record or further on: an
 * instance's variables are not read through the record's field permissions, so routing it would hand its value to
 * everyone who sees the instance.
 */
public final class RecordVariables {

    /** Columns that describe the row rather than what it asks for: its identity, bookkeeping and approval state. */
    private static final Set<String> NOT_ROUTED = Stream.concat(
            Stream.of(ModelConstant.ID, ModelConstant.TENANT_ID, ModelConstant.VERSION, ModelConstant.SOFT_DELETED_FIELD,
                    ApprovalModel.APPROVAL_STATUS, ApprovalModel.SUBMITTER_ID, ApprovalModel.SUBMITTED_TIME),
            ModelConstant.AUDIT_FIELDS.stream())
            .collect(Collectors.toUnmodifiableSet());

    /** The types whose value is one plain thing — or a set of codes — that a route compares. */
    private static final Set<FieldType> ROUTED_TYPES = Set.of(
            FieldType.STRING, FieldType.TEXT, FieldType.INTEGER, FieldType.LONG, FieldType.DOUBLE,
            FieldType.BIG_DECIMAL, FieldType.BOOLEAN, FieldType.DATE, FieldType.DATE_TIME, FieldType.TIME,
            FieldType.OPTION, FieldType.MULTI_OPTION, FieldType.MULTI_STRING,
            FieldType.MANY_TO_ONE, FieldType.ONE_TO_ONE);

    /**
     * How many relations a rule's path may go through: the record's, then the related record's —
     * {@code employeeId.departmentId.costCentreId}. Each hop is a read when the flow starts, and two reach what a route
     * needs of the people and places a record names (the employee's profile, the department's cost centre).
     */
    public static final int MAX_HOPS = 2;

    private RecordVariables() {
    }

    /**
     * The fields of {@code modelName} its approvals route on, in the model's field order; none for a model
     * the metadata does not hold.
     */
    public static List<MetaField> routedFields(String modelName) {
        if (!ModelManager.existModel(modelName)) {
            return List.of();
        }
        return ModelManager.getModelFields(modelName).stream()
                .filter(field -> !field.isDynamic()
                        && ROUTED_TYPES.contains(field.getFieldType())
                        && !NOT_ROUTED.contains(field.getFieldName())
                        && !field.isEncrypted() && field.getMaskingType() == null)
                .toList();
    }

    /**
     * The relations of {@code modelName} a rule reads through: its routed many-to-one and one-to-one fields. A rule
     * names a routed field of the record such a relation points at by its path, {@code employeeId.departmentId}, and —
     * through a routed relation of that record in turn — one more hop on ({@link #MAX_HOPS}).
     */
    public static List<MetaField> routedRelations(String modelName) {
        return routedFields(modelName).stream()
                .filter(field -> FieldType.TO_ONE_TYPES.contains(field.getFieldType())
                        && field.getRelatedModel() != null && ModelManager.existModel(field.getRelatedModel()))
                .toList();
    }

    /**
     * The field {@code path} reads — a routed field of the record reached through one or two routed relations, each of
     * the record before it: {@code employeeId.departmentId}, {@code employeeId.departmentId.costCentreId} — or null when
     * {@code path} is no such path.
     */
    public static @Nullable MetaField routedPathField(String modelName, String path) {
        String[] segments = path.split("\\.", -1);
        if (segments.length < 2 || segments.length > MAX_HOPS + 1) {
            return null;
        }
        String model = modelName;
        for (int i = 0; i < segments.length - 1; i++) {
            MetaField relation = named(routedRelations(model), segments[i]);
            if (relation == null) {
                return null;
            }
            model = relation.getRelatedModel();
        }
        return named(routedFields(model), segments[segments.length - 1]);
    }

    private static @Nullable MetaField named(List<MetaField> fields, String fieldName) {
        return fields.stream().filter(field -> field.getFieldName().equals(fieldName)).findFirst().orElse(null);
    }

    /** The paths {@code rule} reads through a relation: the names it compares that hold a dot. */
    public static Set<String> pathsIn(@Nullable Filters rule) {
        if (Filters.isEmpty(rule)) {
            return Set.of();
        }
        return rule.extractFields().stream()
                .filter(field -> field.indexOf('.') > 0)
                .collect(Collectors.toCollection(TreeSet::new));
    }

    /** The routed fields' values on {@code row}, as a route reads them; a field with no value is left out. */
    public static Map<String, Object> of(String modelName, Map<String, Object> row) {
        Map<String, Object> variables = new LinkedHashMap<>();
        for (MetaField field : routedFields(modelName)) {
            Object value = row.get(field.getFieldName());
            if (value != null) {
                variables.put(field.getFieldName(), routeValue(value));
            }
        }
        return variables;
    }

    /**
     * The values of {@code paths} for the record {@code row} holds, as a route reads them: read now from the records
     * its relations point at, a date or time as ISO text, a path with no value left out. A name that is not a path
     * {@link #routedPathField} accepts is left out too — a rule published before its field changed reads it as not
     * set, as it reads a field the record no longer has.
     *
     * <p>A relation {@code row} does not carry is read from the stored record {@code rowId}: of an update, an entity
     * change brings only what it changed. The reads set the caller's data permissions aside, as the engine's read of
     * the record itself does: a route goes by what the records hold, not by what the submitter may see — and no path
     * ends on a sensitive field.
     *
     * @param modelService reads the records
     * @param modelName    the record's model
     * @param rowId        the record's id, to read a relation {@code row} leaves out; null for a record not saved
     * @param row          the record's values
     * @param paths        the paths to read, as the flow's rules name them
     */
    @SuppressWarnings("unchecked")
    public static Map<String, Object> readPaths(ModelService<?> modelService, String modelName, @Nullable String rowId,
                                                Map<String, Object> row, Collection<String> paths) {
        List<String> readable = paths.stream().filter(path -> routedPathField(modelName, path) != null).toList();
        Map<String, Object> values = new LinkedHashMap<>();
        if (readable.isEmpty()) {
            return values;
        }
        ModelService<Serializable> records = (ModelService<Serializable>) modelService;
        Map<String, Object> references = new LinkedHashMap<>(row);
        List<String> notCarried = readable.stream()
                .map(path -> path.substring(0, path.indexOf('.')))
                .distinct()
                .filter(relation -> !row.containsKey(relation))
                .toList();
        if (!notCarried.isEmpty() && rowId != null) {
            Serializable id = IdUtils.formatId(modelName, rowId);
            ContextUtils.withoutPermissionChecks(() -> records.getById(modelName, id, notCarried))
                    .ifPresent(stored -> notCarried.forEach(relation -> references.put(relation, stored.get(relation))));
        }
        readThrough(records, modelName, references, readable, "", values);
        return values;
    }

    /**
     * Follows each relation {@code paths} start with from the record {@code row} holds: one read of the record it points
     * at, for the paths' fields there and the next relation of those that go on, which are followed the same way. A
     * relation with no value, or pointing at no record, gives its paths no value.
     *
     * @param paths  paths from {@code modelName}, each through at least one relation
     * @param prefix the path to {@code modelName} from the flow's record, ending in a dot; empty there
     */
    private static void readThrough(ModelService<Serializable> records, String modelName, Map<String, Object> row,
                                    List<String> paths, String prefix, Map<String, Object> values) {
        Map<String, List<String>> restByRelation = new LinkedHashMap<>();
        for (String path : paths) {
            int dot = path.indexOf('.');
            restByRelation.computeIfAbsent(path.substring(0, dot), relation -> new ArrayList<>())
                    .add(path.substring(dot + 1));
        }
        restByRelation.forEach((relationName, rests) -> {
            Object key = keyOf(row.get(relationName));
            if (key == null) {
                return;
            }
            MetaField relation = ModelManager.getModelField(modelName, relationName);
            String keyField = relation.getRelatedField() == null ? ModelConstant.ID : relation.getRelatedField();
            List<String> fields = rests.stream().map(rest -> rest.split("\\.", 2)[0]).distinct().toList();
            FlexQuery query = new FlexQuery(fields, new Filters().eq(keyField, key));
            List<Map<String, Object>> related = ContextUtils.withoutPermissionChecks(
                    () -> records.searchList(relation.getRelatedModel(), query));
            if (related.isEmpty()) {
                return;
            }
            Map<String, Object> target = related.getFirst();
            String here = prefix + relationName + ".";
            List<String> further = new ArrayList<>();
            for (String rest : rests) {
                if (rest.indexOf('.') > 0) {
                    further.add(rest);
                } else if (target.get(rest) != null) {
                    values.put(here + rest, routeValue(target.get(rest)));
                }
            }
            if (!further.isEmpty()) {
                readThrough(records, relation.getRelatedModel(), target, further, here, values);
            }
        });
    }

    /**
     * The routed fields' numbers back as numbers, in {@code variables} as an instance stored them — the record's own and
     * those its paths reach. Kept as JSON, an instance's variables come back with every {@code Long} and {@code BigDecimal} as
     * text — the app writes those as strings, so a browser does not round them. Read so, a step after an approval
     * compared an amount with a number and failed, and compared an id with one and never matched (zingkey-hcm#2382). An
     * id stays text where the related model's id is a code. Returns {@code variables}, changed in place.
     */
    public static Map<String, Object> restoreNumbers(String modelName, Map<String, Object> variables) {
        if (modelName == null || variables == null || variables.isEmpty()) {
            return variables;
        }
        for (MetaField field : routedFields(modelName)) {
            restoreNumber(variables, field.getFieldName(), field);
        }
        List<String> paths = variables.keySet().stream().filter(name -> name.indexOf('.') > 0).toList();
        for (String path : paths) {
            MetaField field = routedPathField(modelName, path);
            if (field != null) {
                restoreNumber(variables, path, field);
            }
        }
        return variables;
    }

    private static void restoreNumber(Map<String, Object> variables, String name, MetaField field) {
        if (variables.get(name) instanceof String text && !text.isBlank()) {
            Object number = numberOf(field, text.trim());
            if (number != null) {
                variables.put(name, number);
            }
        }
    }

    /** A value as a route reads it: a date or time as ISO text, anything else as it is. */
    private static Object routeValue(Object value) {
        return value instanceof Temporal ? value.toString() : value;
    }

    /** What a relation's value points at — an id, or a code — whether it is held bare or as a reference. */
    private static @Nullable Object keyOf(@Nullable Object value) {
        if (value instanceof ModelReference reference) {
            return reference.getId();
        }
        if (value instanceof Map<?, ?> reference) {
            return reference.get(ModelConstant.ID);
        }
        return value instanceof String text && text.isBlank() ? null : value;
    }

    /** The number {@code text} stands for in {@code field}, or null where the field holds no number or text none. */
    private static Object numberOf(MetaField field, String text) {
        try {
            return switch (field.getFieldType()) {
                case LONG -> Long.valueOf(text);
                case INTEGER -> Integer.valueOf(text);
                case BIG_DECIMAL -> new BigDecimal(text);
                case DOUBLE -> Double.valueOf(text);
                case MANY_TO_ONE, ONE_TO_ONE -> referencesLongId(field) ? Long.valueOf(text) : null;
                default -> null;
            };
        } catch (NumberFormatException e) {
            return null;
        }
    }

    /** Whether the relation stores a numeric id — not a code, as a currency or a country is known by. */
    private static boolean referencesLongId(MetaField relation) {
        if (relation.getRelatedFieldType() != null) {
            return FieldType.LONG.equals(relation.getRelatedFieldType());
        }
        String related = relation.getRelatedModel();
        return related != null && ModelManager.existField(related, ModelConstant.ID)
                && FieldType.LONG.equals(ModelManager.getModelField(related, ModelConstant.ID).getFieldType());
    }
}
