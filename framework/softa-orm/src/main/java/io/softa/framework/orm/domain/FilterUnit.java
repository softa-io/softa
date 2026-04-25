package io.softa.framework.orm.domain;

import java.io.Serial;
import java.io.Serializable;
import java.util.*;
import java.util.stream.Collectors;
import lombok.Data;
import lombok.NoArgsConstructor;
import org.apache.commons.lang3.StringUtils;

import io.softa.framework.base.enums.Operator;
import io.softa.framework.base.exception.IllegalArgumentException;
import io.softa.framework.base.utils.*;

/**
 * FilterUnit is the smallest component of a filter, represented as a three-element list: [field, operator, value].
 * Example: ["name", "=", "Tony"]. The value can be a single value or a collection value, and the collection value
 * is used for operators such as IN, NOT_IN, BETWEEN, NOT_BETWEEN, PARENT_OF, CHILD_OF. The value can be null when
 * the operator is IS_NULL or IS_NOT_NULL.
 */
@Data
@NoArgsConstructor
public class FilterUnit implements Serializable {

    @Serial
    private static final long serialVersionUID = 1L;

    public static final int UNIT_LENGTH = 3;

    private String field;
    private List<String> fields = new ArrayList<>();
    private Operator operator;
    private Object value;


    public FilterUnit(String field, Operator operator, Object value) {
        this.field = StringUtils.trim(field);
        this.operator = operator;
        if (shouldParseTupleFields(this.field, operator)) {
            this.fields = parseTupleFields(this.field);
            this.field = String.join(",", this.fields);
            this.value = normalizeTupleValue(this.fields.size(), value);
        } else {
            this.value = value;
        }
        validateFilterUnit(this);
    }

    public FilterUnit(Collection<String> fields, Operator operator, Object value) {
        this.fields = normalizeTupleFields(fields);
        this.field = String.join(",", this.fields);
        this.operator = operator;
        this.value = normalizeTupleValue(this.fields.size(), value);
        validateFilterUnit(this);
    }

    /**
     * Create a leaf node with filterUnit parameters
     * @param field field name
     * @param operator operator object
     * @param value value
     * @return FilterUnit
     */
    public static FilterUnit of(String field, Operator operator, Object value) {
        return new FilterUnit(field, operator, value);
    }

    public static FilterUnit of(Collection<String> fields, Operator operator, Object value) {
        return new FilterUnit(fields, operator, value);
    }

    /**
     * Create a leaf node with filterUnit parameters, using Lambda expression as field method to extract field name.
     * @param method method reference, Lambda expression
     * @param operator operator object
     * @param value value
     * @return FilterUnit
     */
    public static <T, R> FilterUnit of(SFunction<T, R> method, Operator operator, Object value) {
        String field = LambdaUtils.getAttributeName(method);
        return of(field, operator, value);
    }

    public boolean isTuple() {
        return fields != null && fields.size() > 1;
    }

    public List<String> getEffectiveFields() {
        if (isTuple()) {
            return Collections.unmodifiableList(fields);
        } else if (StringUtils.isNotBlank(field)) {
            return Collections.singletonList(field);
        }
        return Collections.emptyList();
    }

    /**
     * Validate the FilterUnit format
     * @param filterUnit FilterUnit
     */
    public static void validateFilterUnit(FilterUnit filterUnit) {
        Operator operator = filterUnit.getOperator();
        if (operator == null || filterUnit.getEffectiveFields().isEmpty()) {
            throw new IllegalArgumentException("FilterUnit {0} field name and operator cannot be empty.", filterUnit);
        } else if (filterUnit.isTuple()) {
            validateTupleFilterUnit(filterUnit);
        } else if (filterUnit.getValue() == null && !Operator.ASSIGNED_OPERATORS.contains(operator)) {
            // Inverse the EQUAL/NOT_EQUAL operators with null value, to IS_NOT_SET/IS_SET operators.
            if (Operator.EQUAL.equals(operator)) {
                filterUnit.setOperator(Operator.IS_NOT_SET);
            } else if (Operator.NOT_EQUAL.equals(operator)) {
                filterUnit.setOperator(Operator.IS_SET);
            } else {
                throw new IllegalArgumentException("FilterUnit {0} value cannot be empty.", filterUnit);
            }
        } else if (Operator.COMPARISON_OPERATORS.contains(operator)) {
            validateComparisonOperator(filterUnit);
        } else if (Operator.MATCHING_OPERATORS.contains(operator)) {
            validateMatchingOperators(filterUnit);
        } else if (Operator.COLLECTION_OPERATORS.contains(operator)) {
            validateCollectionValue(filterUnit);
        }
    }

    private static void validateTupleFilterUnit(FilterUnit filterUnit) {
        Operator operator = filterUnit.getOperator();
        Assert.isTrue(Operator.TUPLE_OPERATORS.contains(operator),
                "Tuple filter only supports IN or NOT IN operators: {0}", filterUnit);
        Object value = filterUnit.getValue();
        Assert.isTrue(value instanceof Collection<?>,
                "The value of tuple filter can only be a list: {0}", filterUnit);
        Collection<?> tupleValues = Cast.of(value);
        int tupleSize = filterUnit.getFields().size();
        for (Object tupleValue : tupleValues) {
            Assert.isTrue(tupleValue instanceof Collection<?>,
                    "The value of tuple filter must be a list of tuples: {0}", filterUnit);
            Collection<?> tuple = Cast.of(tupleValue);
            Assert.isTrue(tuple.size() == tupleSize,
                    "The tuple size of filter {0} must be equal to the field size {1}.", filterUnit, tupleSize);
            tuple.forEach(item -> Assert.notNull(item,
                    "Tuple filter does not support null values: {0}", filterUnit));
        }
    }

    /**
     * Validate the value of the comparison operator, which can only be a single value, not a collection
     * @param filterUnit FilterUnit
     */
    private static void validateComparisonOperator(FilterUnit filterUnit) {
        Assert.notTrue(filterUnit.getValue() instanceof Collection,
                "The value of comparison operator can only be a single value: {0}", filterUnit);
    }

    /**
     * Validate the value of the matching operator, which can only be a string type
     * @param filterUnit FilterUnit
     */
    private static void validateMatchingOperators(FilterUnit filterUnit) {
        Object value = filterUnit.getValue();
        Assert.isTrue(value instanceof String strValue && StringUtils.isNotBlank(strValue),
                "The value of matching operator can only be of string type: {0}", filterUnit);
    }

    /**
     * Validate the comparison value of the collection type.
     * Operators: IN, NOT_IN, BETWEEN, NOT_BETWEEN, PARENT_OF, CHILD_OF
     * @param filterUnit FilterUnit
     */
    private static void validateCollectionValue(FilterUnit filterUnit) {
        Operator operator = filterUnit.getOperator();
        if (filterUnit.getValue() instanceof Collection<?> valueList) {
            if ((Operator.PARENT_OF.equals(operator) || Operator.CHILD_OF.equals(operator))) {
                valueList.forEach(v -> Assert.isTrue(v instanceof String strVal && StringUtils.isNotBlank(strVal),
                        "The value of {0} operator can only be a list of non-empty strings: {1}", operator, filterUnit));
            } else if (Operator.BETWEEN.equals(operator) || Operator.NOT_BETWEEN.equals(operator)) {
                Assert.isTrue(valueList.size() == 2,
                        "The value of the {0} operator must be a list of two values: {1}", operator, filterUnit);
            }
        } else {
            throw new IllegalArgumentException("The value of the {0} operator can only be a list: {1}", operator, filterUnit);
        }
    }

    @Override
    public String toString() {
        Object val = formatValue(this.value);
        return String.format("[\"%s\",\"%s\",%s]", this.field, this.operator.getName(), val);
    }

    public String toSemanticString() {
        Object val = formatValue(this.value);
        return String.format("%s %s %s", this.field, this.operator.getName(), val);
    }

    private Object formatValue(Object value) {
        if (value instanceof String strValue) {
            return "\"" + strValue + "\"";
        } else if (value instanceof Number || value instanceof Boolean) {
            return value;
        }
        else {
            return JsonUtils.objectToString(value);
        }
    }

    @Override
    public boolean equals(Object filterUnit) {
        if (filterUnit instanceof FilterUnit filterUnitObj) {
            return Objects.equals(field, filterUnitObj.getField())
                    && Objects.equals(operator, filterUnitObj.getOperator())
                    && Objects.equals(value, filterUnitObj.getValue());
        }
        return false;
    }

    @Override
    public int hashCode() {
        return Objects.hash(field, operator, value);
    }

    public FilterUnit copy() {
        return isTuple() ? FilterUnit.of(fields, operator, value) : FilterUnit.of(field, operator, value);
    }

    private static boolean shouldParseTupleFields(String field, Operator operator) {
        return StringUtils.isNotBlank(field)
                && field.contains(",")
                && operator != null
                && Operator.TUPLE_OPERATORS.contains(operator);
    }

    private static List<String> parseTupleFields(String field) {
        return normalizeTupleFields(List.of(StringUtils.split(field, ",")));
    }

    private static List<String> normalizeTupleFields(Collection<String> fields) {
        Assert.notEmpty(fields, "Tuple filter fields cannot be empty.");
        List<String> normalizedFields = fields.stream()
                .map(StringUtils::trim)
                .collect(Collectors.toList());
        normalizedFields.forEach(field -> Assert.notBlank(field, "Tuple filter field cannot be blank."));
        Assert.isTrue(normalizedFields.size() > 1,
                "Tuple filter must contain at least two fields: {0}", normalizedFields);
        return normalizedFields;
    }

    private static Object normalizeTupleValue(int tupleSize, Object value) {
        Assert.notNull(value, "Tuple filter value cannot be null.");
        Assert.isTrue(value instanceof Collection<?>,
                "The value of tuple filter can only be a list: {0}", value);
        Collection<?> tupleValues = Cast.of(value);
        if (tupleValues.isEmpty()) {
            return value;
        }
        boolean allTupleValues = tupleValues.stream().allMatch(v -> v instanceof Collection<?>);
        if (allTupleValues) {
            return tupleValues.stream()
                    .map(v -> new ArrayList<>((Collection<?>) v))
                    .collect(Collectors.toList());
        }
        Assert.isTrue(tupleValues.size() == tupleSize,
                "The tuple size {0} does not match the field size {1}.", tupleValues.size(), tupleSize);
        return Collections.singletonList(new ArrayList<>(tupleValues));
    }
}
