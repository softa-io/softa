package io.softa.starter.flow.runtime.context;

import java.math.BigDecimal;
import java.time.temporal.Temporal;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;
import java.util.stream.Stream;

import io.softa.framework.orm.constant.ModelConstant;
import io.softa.framework.orm.enums.FieldType;
import io.softa.framework.orm.meta.MetaField;
import io.softa.framework.orm.meta.ModelManager;
import io.softa.starter.flow.entity.ApprovalModel;

/**
 * The variables a record brings to the approval it is submitted to: every field a route can compare —
 * its own columns of a plain type, a date or time as ISO text — under the field's name. The model's
 * handler adds what it works out on top ({@code ModelApprovalHandler#submission}). One selection serves
 * the instance (at submission, and again after an approver edits the record), the designer's variable
 * list and the route-rule check, so a field the designer offers is a field the instance carries.
 *
 * <p>A field declared sensitive — encrypted, or masked when read — is never routed: an instance's
 * variables are not read through the record's field permissions, so routing it would hand its value to
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

    /** The routed fields' values on {@code row}, as a route reads them; a field with no value is left out. */
    public static Map<String, Object> of(String modelName, Map<String, Object> row) {
        Map<String, Object> variables = new LinkedHashMap<>();
        for (MetaField field : routedFields(modelName)) {
            Object value = row.get(field.getFieldName());
            if (value != null) {
                variables.put(field.getFieldName(), value instanceof Temporal ? value.toString() : value);
            }
        }
        return variables;
    }

    /**
     * The routed fields' numbers back as numbers, in {@code variables} as an instance stored them. Kept as JSON, an
     * instance's variables come back with every {@code Long} and {@code BigDecimal} as text — the app writes those
     * as strings, so a browser does not round them. Read so, a step after an approval compared an amount with a
     * number and failed, and compared an id with one and never matched (zingkey-hcm#2382). An id stays text where
     * the related model's id is a code. Returns {@code variables}, changed in place.
     */
    public static Map<String, Object> restoreNumbers(String modelName, Map<String, Object> variables) {
        if (modelName == null || variables == null || variables.isEmpty()) {
            return variables;
        }
        for (MetaField field : routedFields(modelName)) {
            if (variables.get(field.getFieldName()) instanceof String text && !text.isBlank()) {
                Object number = numberOf(field, text.trim());
                if (number != null) {
                    variables.put(field.getFieldName(), number);
                }
            }
        }
        return variables;
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
