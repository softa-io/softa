package io.softa.starter.flow.runtime.context;

import java.io.Serializable;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.jspecify.annotations.Nullable;

import io.softa.framework.base.exception.IllegalArgumentException;
import io.softa.framework.base.utils.Cast;
import io.softa.framework.base.utils.DateUtils;
import io.softa.framework.orm.constant.ModelConstant;
import io.softa.framework.orm.enums.PatchType;
import io.softa.framework.orm.meta.MetaField;
import io.softa.framework.orm.meta.ModelManager;
import io.softa.framework.orm.utils.IdUtils;
import io.softa.framework.orm.utils.OneToManyPatch;

/**
 * A record not saved yet, as a browser sends it, typed as its model's fields hold their values — the values a test
 * run fills in. JSON leaves an id as text (a long id does not fit a JavaScript number), a date as ISO text, an amount
 * as text or a number of the wrong kind, and a table as the rows a new record's form creates; read so, a record's
 * entity cannot be built and a route compares nothing. Typed here as the record would be once saved: numbers, dates,
 * times and booleans by their field's type, a to-one by the type of the id it references (a code stays text), a
 * one-to-many as its rows — a list, or a patch's {@code Create} rows, a new record having none to update or delete —
 * each typed by the related model, a many-to-many as its ids. An empty text is no value. A field the model does not
 * have, or of another type, is kept as sent. A value its field cannot take is refused with the field it was sent for.
 */
public final class SubmittedValues {

    private SubmittedValues() {
    }

    /** {@code values} typed for {@code modelName}; an empty map for none. */
    public static Map<String, Object> typed(String modelName, @Nullable Map<String, Object> values) {
        Map<String, Object> typed = new LinkedHashMap<>();
        if (values == null) {
            return typed;
        }
        boolean known = ModelManager.existModel(modelName);
        values.forEach((fieldName, value) -> typed.put(fieldName,
                known && ModelManager.existField(modelName, fieldName)
                        ? typedValue(ModelManager.getModelField(modelName, fieldName), value)
                        : value));
        return typed;
    }

    private static @Nullable Object typedValue(MetaField field, @Nullable Object value) {
        if (value == null) {
            return null;
        }
        try {
            return switch (field.getFieldType()) {
                case LONG -> number(value, Long.class);
                case INTEGER -> number(value, Integer.class);
                case DOUBLE -> number(value, Double.class);
                case BIG_DECIMAL -> number(value, BigDecimal.class);
                case BOOLEAN -> value instanceof String text ? (text.isBlank() ? null : Boolean.valueOf(text.trim())) : value;
                case DATE -> temporal(value, LocalDate.class);
                case DATE_TIME -> temporal(value, LocalDateTime.class);
                case TIME -> temporal(value, LocalTime.class);
                case MANY_TO_ONE, ONE_TO_ONE -> reference(field, value);
                case ONE_TO_MANY -> rows(field, value);
                case MANY_TO_MANY -> ids(field, value);
                default -> value;
            };
        } catch (NumberFormatException | ArithmeticException | DateTimeParseException e) {
            throw new IllegalArgumentException("Field {0} of {1} cannot take the value {2}: {3}",
                    field.getFieldName(), field.getModelName(), value, e.getMessage());
        }
    }

    private static @Nullable Object number(Object value, Class<? extends Number> type) {
        String text = value instanceof String sent ? sent.trim() : value instanceof Number ? value.toString() : null;
        if (text == null) {
            return value;
        }
        if (text.isEmpty()) {
            return null;
        }
        if (type == Long.class) {
            return new BigDecimal(text).longValueExact();
        }
        if (type == Integer.class) {
            return new BigDecimal(text).intValueExact();
        }
        if (type == Double.class) {
            return Double.valueOf(text);
        }
        return new BigDecimal(text);
    }

    /** A date or time as the API writes it ({@code yyyy-MM-dd HH:mm:ss}), else as ISO text ({@code 09:00} too). */
    private static @Nullable Object temporal(Object value, Class<?> type) {
        if (!(value instanceof String sent)) {
            return value;
        }
        String text = sent.trim();
        if (text.isEmpty()) {
            return null;
        }
        try {
            return DateUtils.stringToDateObject(text, type);
        } catch (DateTimeParseException asTheApiWritesIt) {
            if (type == LocalDateTime.class) {
                return LocalDateTime.parse(text.replace(' ', 'T'));
            }
            return type == LocalTime.class ? LocalTime.parse(text) : LocalDate.parse(text);
        }
    }

    /** A to-one's id — sent bare or as a reference — as the related model holds it. */
    private static @Nullable Object reference(MetaField field, Object value) {
        Object key = value instanceof Map<?, ?> reference ? reference.get(ModelConstant.ID) : value;
        if (key == null || key instanceof String text && text.isBlank()) {
            return null;
        }
        return key instanceof Serializable id && field.getRelatedModel() != null
                ? IdUtils.formatId(field.getRelatedModel(), id) : key;
    }

    /** The rows a new record's one-to-many names, each typed by the related model. */
    private static Object rows(MetaField field, Object value) {
        List<?> rows;
        if (value instanceof List<?> list) {
            rows = list;
        } else if (value instanceof Map<?, ?> patch) {
            rows = OneToManyPatch.parse(patch, field.getModelName(), field.getFieldName()).createRows();
        } else {
            return value;
        }
        List<Object> typedRows = new ArrayList<>(rows.size());
        for (Object row : rows) {
            typedRows.add(row instanceof Map<?, ?> map ? typed(field.getRelatedModel(), Cast.of(map)) : row);
        }
        return typedRows;
    }

    /** A many-to-many's ids — a list, or the ids a patch adds — as the related model holds them. */
    private static Object ids(MetaField field, Object value) {
        Object sent = value instanceof Map<?, ?> patch ? patchPart(patch, PatchType.ADD) : value;
        if (!(sent instanceof List<?> list)) {
            return value;
        }
        List<Object> keys = new ArrayList<>(list.size());
        for (Object item : list) {
            Object key = item instanceof Map<?, ?> reference ? reference.get(ModelConstant.ID) : item;
            if (key != null) {
                keys.add(key);
            }
        }
        return IdUtils.formatIds(field.getRelatedModel(), keys);
    }

    private static @Nullable Object patchPart(Map<?, ?> patch, PatchType type) {
        for (Map.Entry<?, ?> part : patch.entrySet()) {
            if (PatchType.of(String.valueOf(part.getKey())) == type) {
                return part.getValue();
            }
        }
        return null;
    }
}
